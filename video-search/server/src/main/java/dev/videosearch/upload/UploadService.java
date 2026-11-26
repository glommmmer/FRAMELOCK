package dev.videosearch.upload;

import dev.videosearch.api.ApiError;
import dev.videosearch.media.MediaInspector;
import dev.videosearch.storage.ObjectStorage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UploadService {
  public static final int PART_BYTES = 5 * 1024 * 1024;
  private final JdbcTemplate db;
  private final ObjectStorage storage;
  private final MediaInspector inspector;
  private final TransactionTemplate tx;
  private final Path temporary;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<org.redisson.api.RedissonClient> redis;

  public UploadService(
      JdbcTemplate db,
      ObjectStorage storage,
      MediaInspector inspector,
      TransactionTemplate tx,
      @Value("${app.data-dir:.data}") String directory)
      throws IOException {
    this.db = db;
    this.storage = storage;
    this.inspector = inspector;
    this.tx = tx;
    temporary = Path.of(directory).resolve("uploads").toAbsolutePath();
    Files.createDirectories(temporary);
  }

  public record Initialize(String libraryId, String name, long sizeBytes, String sha256) {}

  @Transactional
  public Object initialize(String owner, Initialize input) {
    if (input.sizeBytes() <= 0 || input.sizeBytes() > 2L * 1024 * 1024 * 1024)
      throw ApiError.bad("单视频最大2 GiB");
    if (input.name() == null
        || input.name().isBlank()
        || input.name().length() > 250
        || !input.name().toLowerCase(Locale.ROOT).endsWith(".mp4")) throw ApiError.bad("请上传MP4文件");
    if (input.sha256() == null || !input.sha256().matches("[a-fA-F0-9]{64}"))
      throw ApiError.bad("需要完整文件的SHA-256");
    if (db.queryForList(
            "SELECT id FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE FOR UPDATE",
            input.libraryId(),
            owner)
        .isEmpty()) throw ApiError.missing();
    long occupied =
        db.queryForObject(
                "SELECT COUNT(*) FROM vs_video WHERE library_id=? AND deleted=FALSE",
                Long.class,
                input.libraryId())
            + db.queryForObject(
                "SELECT COUNT(*) FROM vs_upload WHERE library_id=? AND status IN ('OPEN','MERGING')"
                    + " AND expires_at>CURRENT_TIMESTAMP",
                Long.class,
                input.libraryId());
    if (occupied >= 10) throw ApiError.conflict("素材库最多10个视频，含正在上传的视频");
    String id = UUID.randomUUID().toString(), video = UUID.randomUUID().toString();
    int count = (int) ((input.sizeBytes() + PART_BYTES - 1) / PART_BYTES);
    db.update(
        "INSERT INTO"
            + " vs_upload(id,video_id,owner,library_id,name,file_size,file_hash,total_parts,status,expires_at,storage_backend)"
            + " VALUES(?,?,?,?,?,?,?,?,'OPEN',?,?)",
        id,
        video,
        owner,
        input.libraryId(),
        input.name(),
        input.sizeBytes(),
        input.sha256().toLowerCase(Locale.ROOT),
        count,
        Timestamp.from(Instant.now().plusSeconds(86400)),
        storage.backend());
    return Map.of("id", id, "videoId", video, "partSizeBytes", PART_BYTES, "totalParts", count);
  }

  public Map<String, Object> owned(String owner, String id) {
    return db.queryForList("SELECT * FROM vs_upload WHERE id=? AND owner=?", id, owner).stream()
        .findFirst()
        .orElseThrow(ApiError::missing);
  }

  public Object state(String owner, String id) {
    var upload = owned(owner, id);
    return Map.of(
        "id",
        id,
        "status",
        upload.get("status"),
        "totalParts",
        upload.get("total_parts"),
        "parts",
        db.queryForList(
            "SELECT part_no,size_bytes,file_hash FROM vs_upload_part WHERE upload_id=? ORDER BY"
                + " part_no",
            id));
  }

  public Object part(String owner, String id, int number, InputStream input, String expectedHash)
      throws Exception {
    var upload = owned(owner, id);
    if (!upload.get("status").equals("OPEN")
        || ((Timestamp) upload.get("expires_at")).toInstant().isBefore(Instant.now()))
      throw ApiError.conflict("上传任务已结束或过期");
    int total = ((Number) upload.get("total_parts")).intValue();
    if (number < 0 || number >= total) throw ApiError.bad("分片编号无效");
    long size = ((Number) upload.get("file_size")).longValue();
    long expected = Math.min(PART_BYTES, size - (long) number * PART_BYTES);
    Path path = Files.createTempFile(temporary, "part-", ".tmp");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long written = 0;
      try (OutputStream out = Files.newOutputStream(path)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          written += count;
          if (written > expected) throw ApiError.bad("分片大小超过声明范围");
          digest.update(buffer, 0, count);
          out.write(buffer, 0, count);
        }
      }
      if (written != expected) throw ApiError.bad("分片大小不匹配");
      String hash = HexFormat.of().formatHex(digest.digest());
      if (expectedHash != null && !hash.equalsIgnoreCase(expectedHash))
        throw ApiError.bad("分片校验不通过");
      String key =
          ObjectStorage.tagged(
              upload.get("storage_backend").toString(),
              "video-search/parts/" + id + "/" + number + "/" + hash);
      // Persist intent before OSS: even a crash before the receipt leaves a cleanup key.
      try {
        db.update("INSERT INTO vs_upload_object(object_key,upload_id) VALUES(?,?)", key, id);
      } catch (org.springframework.dao.DuplicateKeyException ignored) {
      }
      storage.put(key, path);
      final long finalSize = written;
      tx.executeWithoutResult(
          status -> {
            var current =
                db.queryForList(
                    "SELECT status FROM vs_upload WHERE id=? AND owner=? FOR UPDATE", id, owner);
            if (current.isEmpty() || !current.getFirst().get("status").equals("OPEN"))
              throw ApiError.conflict("任务已开始合并");
            var prior =
                db.queryForList(
                    "SELECT file_hash FROM vs_upload_part WHERE upload_id=? AND part_no=?",
                    id,
                    number);
            if (!prior.isEmpty()) {
              if (!hash.equals(prior.getFirst().get("file_hash")))
                throw ApiError.conflict("同一分片编号不能替换为不同内容");
            } else
              db.update(
                  "INSERT INTO vs_upload_part(upload_id,part_no,size_bytes,file_hash,object_key)"
                      + " VALUES(?,?,?,?,?)",
                  id,
                  number,
                  finalSize,
                  hash,
                  key);
          });
      return Map.of("partNo", number, "sha256", hash, "sizeBytes", written);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  public Object complete(String owner, String id) throws Exception {
    var upload = owned(owner, id);
    if (upload.get("status").equals("COMPLETE"))
      return receipt(owner, upload.get("video_id").toString());
    var client = redis.getIfAvailable();
    org.redisson.api.RLock lock = null;
    boolean held = false;
    try {
      if (client != null) {
        lock = client.getLock("video-search:merge:" + id);
        try {
          held = lock.tryLock(0, java.util.concurrent.TimeUnit.SECONDS);
        } catch (org.redisson.client.RedisException e) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("Merge lock unavailable; using database execution fencing");
          lock = null;
        }
        if (lock != null && !held) throw ApiError.conflict("任务正在合并");
      }
      return completeFenced(owner, id);
    } finally {
      if (held && lock != null)
        try {
          if (lock.isHeldByCurrentThread()) lock.unlock();
        } catch (org.redisson.client.RedisException e) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("Merge lock release deferred to watchdog expiry");
        }
    }
  }

  private Object completeFenced(String owner, String id) throws Exception {
    var upload = owned(owner, id);
    String video = (String) upload.get("video_id");
    if (upload.get("status").equals("COMPLETE")) return receipt(owner, video);
    String execution = UUID.randomUUID().toString();
    int changed =
        db.update(
            "UPDATE vs_upload SET status='MERGING',execution_id=?,lease_until=? WHERE id=? AND"
                + " owner=? AND expires_at>CURRENT_TIMESTAMP AND (status='OPEN' OR"
                + " (status='MERGING' AND lease_until<CURRENT_TIMESTAMP))",
            execution,
            Timestamp.from(Instant.now().plusSeconds(1800)),
            id,
            owner);
    if (changed == 0) throw ApiError.conflict("任务正在合并或已过期");
    Path merged = Files.createTempFile(temporary, "merged-", ".mp4");
    try {
      var parts =
          db.queryForList("SELECT * FROM vs_upload_part WHERE upload_id=? ORDER BY part_no", id);
      if (parts.size() != ((Number) upload.get("total_parts")).intValue())
        throw ApiError.conflict("仍有分片未上传");
      var digest = MessageDigest.getInstance("SHA-256");
      long written = 0;
      try (OutputStream out = Files.newOutputStream(merged)) {
        byte[] buffer = new byte[65536];
        for (var part : parts) {
          try (InputStream in = storage.open((String) part.get("object_key"))) {
            int n;
            while ((n = in.read(buffer)) != -1) {
              written += n;
              digest.update(buffer, 0, n);
              out.write(buffer, 0, n);
            }
          }
        }
      }
      if (written != ((Number) upload.get("file_size")).longValue()
          || !HexFormat.of().formatHex(digest.digest()).equals(upload.get("file_hash")))
        throw ApiError.bad("完整视频校验失败");
      var info = inspector.inspect(merged);
      String key =
          ObjectStorage.tagged(
              upload.get("storage_backend").toString(),
              "video-search/videos/" + video + "/original.mp4");
      storage.put(key, merged);
      String task = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
      tx.executeWithoutResult(
          status -> {
            if (db.update(
                    "UPDATE vs_upload SET status='COMPLETE',lease_until=NULL WHERE id=? AND"
                        + " status='MERGING' AND execution_id=?",
                    id,
                    execution)
                != 1) throw ApiError.conflict("合并执行已失效");
            db.update(
                "INSERT INTO"
                    + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,'PENDING')",
                video,
                upload.get("library_id"),
                owner,
                upload.get("name"),
                key,
                upload.get("file_hash"),
                upload.get("file_size"),
                info.durationMs(),
                info.hasAudio());
            db.update(
                "INSERT INTO"
                    + " vs_task(id,owner,video_id,version_id,kind,stage,status,idempotency_key)"
                    + " VALUES(?,?,?,?,'PARSE','PROBE','PENDING',?)",
                task,
                owner,
                video,
                version,
                video + ":" + version + ":PROBE");
            db.update(
                "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                UUID.randomUUID().toString(),
                task);
          });
      return Map.of("videoId", video, "taskId", task, "status", "PENDING");
    } catch (Exception error) {
      db.update(
          "UPDATE vs_upload SET status='OPEN',execution_id=NULL,lease_until=NULL WHERE id=? AND"
              + " status='MERGING' AND execution_id=?",
          id,
          execution);
      throw error;
    } finally {
      Files.deleteIfExists(merged);
    }
  }

  private Object receipt(String owner, String video) {
    var task =
        db
            .queryForList(
                "SELECT id,status FROM vs_task WHERE owner=? AND video_id=? AND kind='PARSE' ORDER"
                    + " BY created_at,id",
                owner,
                video)
            .stream()
            .findFirst()
            .orElseThrow(ApiError::missing);
    return Map.of("videoId", video, "taskId", task.get("id"), "status", task.get("status"));
  }
}
