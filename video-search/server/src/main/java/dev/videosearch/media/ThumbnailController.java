package dev.videosearch.media;

import dev.videosearch.api.ApiError;
import dev.videosearch.model.ProcessingModels;
import java.nio.file.Files;
import java.security.Principal;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/videos")
public class ThumbnailController {
  private final JdbcTemplate db;
  private final MediaCache media;
  private final ProcessingModels models;

  public ThumbnailController(JdbcTemplate db, MediaCache media, ProcessingModels models) {
    this.db = db;
    this.media = media;
    this.models = models;
  }

  @GetMapping("/{id}/thumbnail")
  ResponseEntity<byte[]> get(
      Principal user, @PathVariable String id, @RequestParam(defaultValue = "0") long timeMs)
      throws Exception {
    var rows =
        db.queryForList(
            "SELECT * FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE", id, user.getName());
    if (rows.isEmpty()) throw ApiError.missing();
    var video = rows.getFirst();
    long duration = ((Number) video.get("duration_ms")).longValue();
    if (timeMs < 0 || timeMs >= duration) throw ApiError.bad("缩略图时间超出视频范围");
    String hash = video.get("file_hash").toString();
    var frames =
        models
            .sampler()
            .sample(
                media.video(video.get("object_key").toString(), hash),
                hash,
                timeMs,
                Math.min(duration, timeMs + 1000),
                1000);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE",
            Long.class,
            id,
            user.getName())
        == 0) throw ApiError.missing();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.IMAGE_JPEG)
        .body(Files.readAllBytes(frames.getFirst().image()));
  }
}
