package dev.videosearch.media;

import dev.videosearch.api.ApiError;
import dev.videosearch.storage.ObjectStorage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {
  private final JdbcTemplate db;
  private final ObjectStorage storage;
  private final TransactionTemplate tx;

  public VideoController(JdbcTemplate db, ObjectStorage storage, TransactionTemplate tx) {
    this.db = db;
    this.storage = storage;
    this.tx = tx;
  }

  private Map<String, Object> owned(String owner, String id) {
    return db
        .queryForList("SELECT * FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE", id, owner)
        .stream()
        .findFirst()
        .orElseThrow(ApiError::missing);
  }

  @GetMapping("/{id}/content")
  ResponseEntity<StreamingResponseBody> content(
      Principal user,
      @PathVariable String id,
      @RequestHeader(value = "Range", required = false) String header) {
    var video = owned(user.getName(), id);
    long size = ((Number) video.get("file_size")).longValue();
    var range = ByteRange.parse(header, size);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.valueOf("video/mp4"));
    headers.setContentLength(range.length());
    headers.set("Accept-Ranges", "bytes");
    headers.setCacheControl("no-store");
    headers.setContentDisposition(
        ContentDisposition.inline()
            .filename((String) video.get("name"), StandardCharsets.UTF_8)
            .build());
    if (range.partial())
      headers.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + size);
    StreamingResponseBody body =
        out -> {
          // Recheck immediately before opening the object after async dispatch.
          owned(user.getName(), id);
          try (InputStream input =
              storage.openRange((String) video.get("object_key"), range.start(), range.end())) {
            long remaining = range.length();
            byte[] buffer = new byte[65536];
            int count;
            while (remaining > 0
                && (count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining)))
                    != -1) {
              out.write(buffer, 0, count);
              remaining -= count;
            }
          }
        };
    return new ResponseEntity<>(
        body, headers, range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
  }

  @DeleteMapping("/{id}")
  ResponseEntity<?> delete(Principal user, @PathVariable String id) {
    owned(user.getName(), id);
    String task = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        status -> {
          if (db.update(
                  "UPDATE vs_video SET deleted=TRUE,status='DELETED' WHERE id=? AND owner=? AND"
                      + " deleted=FALSE",
                  id,
                  user.getName())
              != 1) return;
          db.update(
              "UPDATE vs_task SET cancel_requested=TRUE,status=CASE WHEN status IN"
                  + " ('PENDING','RETRY_WAIT','WAITING') THEN 'CANCELLED' ELSE status END WHERE"
                  + " video_id=? AND status IN ('PENDING','RUNNING','RETRY_WAIT','WAITING')",
              id);
          db.update("DELETE FROM vs_favorite WHERE video_id=?", id);
          db.update(
              "INSERT INTO vs_task(id,owner,video_id,kind,stage,status,idempotency_key)"
                  + " VALUES(?,?,?,'CLEANUP','CLEANUP','PENDING',?)",
              task,
              user.getName(),
              id,
              "delete:" + id);
          db.update(
              "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)", UUID.randomUUID().toString(), task);
        });
    return ResponseEntity.accepted().body(Map.of("taskId", task, "status", "DELETED"));
  }
}
