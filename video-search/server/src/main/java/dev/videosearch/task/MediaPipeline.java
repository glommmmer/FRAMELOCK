package dev.videosearch.task;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.MediaCache;
import dev.videosearch.model.*;
import dev.videosearch.search.QdrantIndex;
import dev.videosearch.storage.ObjectStorage;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MediaPipeline implements StageProcessor {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PersistentTasks tasks;
  private final MediaCache media;
  private final ProcessingModels models;
  private final ObjectStorage storage;
  private final AsrGateway asr;
  private final QdrantIndex index;
  private final ObjectProvider<SearchProcessor> searches;

  @org.springframework.beans.factory.annotation.Value("${app.index-mode:qdrant}")
  private String indexMode;

  public MediaPipeline(
      JdbcTemplate db,
      TransactionTemplate tx,
      PersistentTasks tasks,
      MediaCache media,
      ProcessingModels models,
      ObjectStorage storage,
      AsrGateway asr,
      QdrantIndex index,
      ObjectProvider<SearchProcessor> searches) {
    this.db = db;
    this.tx = tx;
    this.tasks = tasks;
    this.media = media;
    this.models = models;
    this.storage = storage;
    this.asr = asr;
    this.index = index;
    this.searches = searches;
  }

  @Override
  public void process(PersistentTasks.Lease lease) throws Exception {
    if (lease.row().get("kind").equals("SEARCH")) {
      var search = searches.getIfAvailable();
      if (search == null) throw new IllegalArgumentException("Search handler unavailable");
      search.process(lease);
      return;
    }
    String stage = (String) lease.row().get("stage");
    if (stage.equals("UPLOAD_CLEANUP")) {
      String upload =
          JsonFiles.JSON.readTree((String) lease.row().get("input_json")).path("uploadId").asText();
      if (db.queryForObject(
              "SELECT COUNT(*) FROM vs_upload WHERE id=? AND owner=? AND status IN"
                  + " ('EXPIRED','COMPLETE')",
              Long.class,
              upload,
              lease.row().get("owner"))
          != 1) throw new IllegalArgumentException("Upload cleanup scope invalid");
      var keys =
          db.queryForList(
              "SELECT object_key FROM vs_upload_part WHERE upload_id=? UNION SELECT object_key FROM"
                  + " vs_upload_object WHERE upload_id=? AND cleaned=FALSE",
              String.class,
              upload,
              upload);
      storage.deleteUploadParts(upload, keys);
      tasks.succeed(
          lease,
          Map.of("uploadPartsCleaned", true),
          () -> {
            db.update("DELETE FROM vs_upload_part WHERE upload_id=?", upload);
            for (String key : keys)
              db.update("UPDATE vs_upload_object SET cleaned=TRUE WHERE object_key=?", key);
          });
      return;
    }
    if (stage.startsWith("ASR")
        && lease.row().get("created_at") instanceof java.sql.Timestamp created
        && created.toInstant().isBefore(java.time.Instant.now().minusSeconds(3600)))
      throw new IllegalArgumentException("ASR processing exceeded one hour");
    if (stage.equals("CLEANUP")) {
      cleanup(lease);
      return;
    }
    var video = source(lease);
    String hash = (String) video.get("file_hash");
    var path = media.video((String) video.get("object_key"), hash);
    String version = (String) lease.row().get("version_id");
    long duration = ((Number) video.get("duration_ms")).longValue();
    switch (stage) {
      case "PROBE" ->
          tasks.awaitChildren(
              lease,
              () -> {
                for (long start = 0; start < duration; start += 5000) {
                  if (start > 0 && duration - start <= 5000) break;
                  child(
                      lease,
                      "VISUAL",
                      Map.of("startMs", start, "endMs", Math.min(duration, start + 10000)),
                      "visual:" + start);
                }
                if (Boolean.TRUE.equals(video.get("has_audio")))
                  child(lease, "ASR_SUBMIT", Map.of(), "asr");
              });
      case "VISUAL" -> {
        var input = JsonFiles.JSON.readTree((String) lease.row().get("input_json"));
        long start = input.path("startMs").asLong(), end = input.path("endMs").asLong();
        JsonNode description = models.describe(path, hash, start, end);
        String summary = description.path("summary").asText();
        if (summary.isBlank()) throw new IllegalStateException("Missing visual summary");
        String searchable = summary + "\n" + description.path("actions");
        var embedding = models.embed(searchable);
        String segment =
            UUID.nameUUIDFromBytes(
                    (video.get("id") + ":" + version + ":" + start)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        String vector = JsonFiles.JSON.writeValueAsString(embedding),
            evidence = JsonFiles.JSON.writeValueAsString(description);
        tasks.succeed(
            lease,
            Map.of("segmentId", segment),
            () ->
                db.update(
                    "INSERT INTO"
                        + " vs_segment(id,video_id,version_id,owner,library_id,start_ms,end_ms,description,ocr_text,evidence_json,embedding)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    segment,
                    video.get("id"),
                    version,
                    video.get("owner"),
                    video.get("library_id"),
                    start,
                    end,
                    searchable,
                    description.path("ocrTexts").toString(),
                    evidence,
                    vector));
      }
      case "ASR_SUBMIT" -> {
        var audio = media.audio(path, hash);
        String audioKey = "video-search/audio/" + video.get("id") + "/" + version + ".wav";
        storage.put(audioKey, audio);
        String job = asr.submit(storage.signedGet(audioKey), hash, duration);
        tasks.defer(lease, "ASR_POLL", Map.of("jobId", job, "audioKey", audioKey), 10);
      }
      case "ASR_POLL" -> {
        var input = JsonFiles.JSON.readTree((String) lease.row().get("input_json"));
        var state = asr.status(input.path("jobId").asText());
        String status = state.path("output").path("task_status").asText();
        if (status.equals("FAILED")) throw new IllegalArgumentException("ASR_JOB_FAILED");
        if (!status.equals("SUCCEEDED")) {
          tasks.defer(lease, "ASR_POLL", JsonFiles.JSON.convertValue(input, Map.class), 10);
          return;
        }
        List<Map<String, Object>> cues = new ArrayList<>();
        for (var file : state.path("output").path("results")) {
          if (!file.path("subtask_status").asText().equals("SUCCEEDED")) continue;
          var transcript = asr.result(file.path("transcription_url").asText());
          for (var channel : transcript.path("transcripts"))
            for (var sentence : channel.path("sentences")) {
              long start = sentence.path("begin_time").asLong(),
                  end = sentence.path("end_time").asLong();
              String text = sentence.path("text").asText();
              if (start >= 0 && end > start && end <= duration && !text.isBlank())
                cues.add(Map.of("start", start, "end", end, "text", text));
            }
        }
        tasks.succeed(
            lease,
            Map.of("cues", cues.size()),
            () -> {
              for (var cue : cues)
                db.update(
                    "INSERT INTO vs_transcript(id,video_id,version_id,start_ms,end_ms,content)"
                        + " VALUES(?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    video.get("id"),
                    version,
                    cue.get("start"),
                    cue.get("end"),
                    cue.get("text"));
            });
      }
      case "INDEX" -> {
        if (!indexMode.equals("local")) index.ensureCollection();
        var rows =
            db.queryForList(
                "SELECT * FROM vs_segment WHERE video_id=? AND version_id=? ORDER BY start_ms",
                video.get("id"),
                version);
        for (int offset = 0; offset < rows.size(); offset += 32) {
          if (!tasks.active(lease))
            throw new IllegalArgumentException("Index task cancelled or replaced");
          List<QdrantIndex.Point> points = new ArrayList<>();
          for (var row : rows.subList(offset, Math.min(rows.size(), offset + 32))) {
            List<Double> vector = new ArrayList<>();
            for (var value : JsonFiles.JSON.readTree((String) row.get("embedding")))
              vector.add(value.asDouble());
            points.add(
                new QdrantIndex.Point(
                    (String) row.get("id"),
                    (String) video.get("id"),
                    version,
                    (String) video.get("owner"),
                    (String) video.get("library_id"),
                    ((Number) row.get("start_ms")).longValue(),
                    ((Number) row.get("end_ms")).longValue(),
                    vector));
          }
          if (!indexMode.equals("local")) index.upsert(points, lease.executionId());
        }
        tasks.succeed(
            lease,
            Map.of("segments", rows.size()),
            () -> {
              String parent = (String) lease.row().get("parent_id");
              if (db.queryForList(
                      "SELECT id FROM vs_task WHERE id=? AND status='WAITING' AND"
                          + " cancel_requested=FALSE FOR UPDATE",
                      parent)
                  .isEmpty()) throw new IllegalArgumentException("Parent no longer active");
              long warnings =
                  db.queryForObject(
                      "SELECT COUNT(*) FROM vs_task WHERE parent_id=? AND stage LIKE 'ASR%' AND"
                          + " status='FAILED'",
                      Long.class, parent);
              if (db.update(
                      "UPDATE vs_video SET status=?,active_version=?,index_execution_id=? WHERE"
                          + " id=? AND deleted=FALSE",
                      warnings > 0 ? "READY_PARTIAL" : "READY",
                      version,
                      lease.executionId(),
                      video.get("id"))
                  != 1) throw new IllegalArgumentException("Video deleted");
              if (!indexMode.equals("local"))
                db.update(
                    "INSERT INTO vs_index_sync(video_id,version_id,execution_id) VALUES(?,?,?)",
                    video.get("id"),
                    version,
                    lease.executionId());
              db.update(
                  "UPDATE vs_task SET"
                      + " status='SUCCEEDED',stage='PUBLISHED',output_json=?,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status='WAITING'",
                  "{\"asrWarnings\":" + warnings + "}",
                  parent);
            });
      }
      default -> throw new IllegalArgumentException("Unsupported processing stage");
    }
  }

  private Map<String, Object> source(PersistentTasks.Lease lease) {
    var video =
        db
            .queryForList(
                "SELECT * FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE",
                lease.row().get("video_id"),
                lease.row().get("owner"))
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Video deleted"));
    Object parent = lease.row().get("parent_id");
    if (parent != null
        && db.queryForObject(
                "SELECT COUNT(*) FROM vs_task WHERE id=? AND status='WAITING' AND"
                    + " cancel_requested=FALSE",
                Long.class,
                parent)
            == 0) throw new IllegalArgumentException("Parent cancelled");
    return video;
  }

  private void child(PersistentTasks.Lease parent, String stage, Object input, String suffix) {
    String id = UUID.randomUUID().toString();
    try {
      db.update(
          "INSERT INTO"
              + " vs_task(id,owner,video_id,version_id,parent_id,kind,stage,status,input_json,idempotency_key)"
              + " VALUES(?,?,?,?,?,'PARSE',?,'PENDING',?,?)",
          id,
          parent.row().get("owner"),
          parent.row().get("video_id"),
          parent.row().get("version_id"),
          parent.id(),
          stage,
          JsonFiles.JSON.writeValueAsString(input),
          parent.id() + ":" + suffix);
      db.update("INSERT INTO vs_outbox(id,task_id) VALUES(?,?)", UUID.randomUUID().toString(), id);
    } catch (org.springframework.dao.DuplicateKeyException ignored) {
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Scheduled(fixedDelay = 3000)
  public void coordinate() {
    for (var parent :
        db.queryForList(
            "SELECT * FROM vs_task WHERE kind='PARSE' AND status='WAITING' ORDER BY created_at"
                + " LIMIT 20"))
      tx.executeWithoutResult(
          status -> {
            String id = (String) parent.get("id");
            var locked =
                db.queryForList(
                    "SELECT * FROM vs_task WHERE id=? AND status='WAITING' FOR UPDATE", id);
            if (locked.isEmpty()) return;
            var children =
                db.queryForList("SELECT stage,status FROM vs_task WHERE parent_id=?", id);
            if (children.stream()
                .anyMatch(
                    c ->
                        !((String) c.get("stage")).startsWith("ASR")
                            && List.of("FAILED", "CANCELLED").contains(c.get("status")))) {
              db.update(
                  "UPDATE vs_task SET status='FAILED',error_message='A required processing stage"
                      + " failed',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                  id);
              return;
            }
            if (!parent.get("stage").equals("PROBE")
                || children.isEmpty()
                || children.stream()
                    .anyMatch(
                        c ->
                            !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(c.get("status"))))
              return;
            db.update(
                "UPDATE vs_task SET stage='INDEX',updated_at=CURRENT_TIMESTAMP WHERE id=? AND"
                    + " status='WAITING'",
                id);
            child(new PersistentTasks.Lease(id, "", parent), "INDEX", Map.of(), "index");
          });
  }

  private void cleanup(PersistentTasks.Lease lease) throws Exception {
    var videos =
        db.queryForList(
            "SELECT * FROM vs_video WHERE id=? AND owner=? AND deleted=TRUE",
            lease.row().get("video_id"),
            lease.row().get("owner"));
    if (videos.isEmpty()) {
      tasks.succeed(lease, Map.of(), () -> {});
      return;
    }
    var video = videos.getFirst();
    if (!indexMode.equals("local"))
      index.deleteVideo((String) video.get("owner"), (String) video.get("id"));
    storage.delete((String) video.get("object_key"));
    for (var part :
        db.queryForList(
            "SELECT p.object_key FROM vs_upload_part p JOIN vs_upload u ON u.id=p.upload_id WHERE"
                + " u.video_id=?",
            video.get("id"))) storage.delete((String) part.get("object_key"));
    if (Boolean.TRUE.equals(video.get("has_audio")))
      for (var version :
          db.queryForList(
              "SELECT DISTINCT version_id FROM vs_task WHERE video_id=? AND kind='PARSE' AND"
                  + " version_id IS NOT NULL",
              video.get("id")))
        storage.delete(
            "video-search/audio/" + video.get("id") + "/" + version.get("version_id") + ".wav");
    tasks.succeed(
        lease,
        Map.of("deleted", true),
        () -> {
          db.update("DELETE FROM vs_segment WHERE video_id=?", video.get("id"));
          db.update("DELETE FROM vs_transcript WHERE video_id=?", video.get("id"));
        });
  }
}
