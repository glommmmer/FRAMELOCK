package dev.videosearch.library;

import dev.videosearch.api.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {
  private final JdbcTemplate db;

  public FavoriteController(JdbcTemplate db) {
    this.db = db;
  }

  public record Create(
      @NotBlank String videoId, long startMs, long endMs, @Size(max = 36) String folderId) {}

  public record Move(@Size(max = 36) String folderId) {}

  private String ownedFolder(String owner, String folderId) {
    if (folderId == null || folderId.isBlank()) return null;
    if (db.queryForList(
            "SELECT id FROM vs_favorite_folder WHERE id=? AND owner=? FOR UPDATE", folderId, owner)
        .isEmpty()) throw ApiError.missing();
    return folderId;
  }

  @PostMapping
  @Transactional
  Object create(Principal user, @Valid @RequestBody Create request) {
    String folderId = ownedFolder(user.getName(), request.folderId());
    var videos =
        db.queryForList(
            "SELECT duration_ms FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE",
            request.videoId(),
            user.getName());
    if (videos.isEmpty()) throw ApiError.missing();
    if (request.startMs() < 0
        || request.endMs() <= request.startMs()
        || request.endMs() > ((Number) videos.getFirst().get("duration_ms")).longValue())
      throw ApiError.bad("收藏时间超出视频范围");
    String id = UUID.randomUUID().toString();
    try {
      db.update(
          "INSERT INTO vs_favorite(id,owner,video_id,start_ms,end_ms,folder_id)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          user.getName(),
          request.videoId(),
          request.startMs(),
          request.endMs(),
          folderId);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      id =
          db.queryForObject(
              "SELECT id FROM vs_favorite WHERE owner=? AND video_id=? AND start_ms=? AND end_ms=?",
              String.class,
              user.getName(),
              request.videoId(),
              request.startMs(),
              request.endMs());
      db.update(
          "UPDATE vs_favorite SET folder_id=? WHERE id=? AND owner=?",
          folderId,
          id,
          user.getName());
    }
    return Map.of("id", id);
  }

  @GetMapping
  Object list(Principal user) {
    return db.queryForList(
        "SELECT f.id,f.video_id,f.start_ms,f.end_ms,f.folder_id,v.name,v.library_id,l.name AS"
            + " library_name FROM vs_favorite f JOIN vs_video v ON v.id=f.video_id JOIN vs_library"
            + " l ON l.id=v.library_id AND l.owner=v.owner WHERE f.owner=? AND v.owner=? AND"
            + " v.deleted=FALSE AND l.deleted=FALSE ORDER BY f.created_at DESC,f.id",
        user.getName(),
        user.getName());
  }

  @PatchMapping("/{id}/folder")
  @Transactional
  Object move(Principal user, @PathVariable String id, @Valid @RequestBody Move request) {
    String folderId = ownedFolder(user.getName(), request.folderId());
    if (db.queryForList(
            "SELECT f.id FROM vs_favorite f JOIN vs_video v ON v.id=f.video_id WHERE f.id=? AND"
                + " f.owner=? AND v.owner=? AND v.deleted=FALSE",
            id,
            user.getName(),
            user.getName())
        .isEmpty()) throw ApiError.missing();
    db.update(
        "UPDATE vs_favorite SET folder_id=? WHERE id=? AND owner=?", folderId, id, user.getName());
    return Map.of("id", id);
  }

  @DeleteMapping("/{id}")
  void delete(Principal user, @PathVariable String id) {
    db.update("DELETE FROM vs_favorite WHERE id=? AND owner=?", id, user.getName());
  }
}
