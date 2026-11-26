package dev.videosearch.library;

import dev.videosearch.api.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/favorite-folders")
public class FavoriteFolderController {
  private final JdbcTemplate db;

  public FavoriteFolderController(JdbcTemplate db) {
    this.db = db;
  }

  public record Create(@NotBlank @Size(max = 80) String name) {}

  @PostMapping
  Object create(Principal user, @Valid @RequestBody Create body) {
    String id = UUID.randomUUID().toString(), name = body.name().trim();
    try {
      db.update(
          "INSERT INTO vs_favorite_folder(id,owner,name) VALUES(?,?,?)", id, user.getName(), name);
    } catch (DuplicateKeyException e) {
      throw ApiError.conflict("已经有同名的收藏夹了，换个名字试试。");
    }
    return Map.of("id", id, "name", name);
  }

  @GetMapping
  Object list(Principal user) {
    return db.queryForList(
        "SELECT id,name,created_at FROM vs_favorite_folder WHERE owner=? ORDER BY created_at,id",
        user.getName());
  }

  private void lockOwned(String owner, String id) {
    if (db.queryForList(
            "SELECT id FROM vs_favorite_folder WHERE id=? AND owner=? FOR UPDATE", id, owner)
        .isEmpty()) throw ApiError.missing();
  }

  @PatchMapping("/{id}")
  @Transactional
  Object rename(Principal user, @PathVariable String id, @Valid @RequestBody Create body) {
    lockOwned(user.getName(), id);
    try {
      db.update(
          "UPDATE vs_favorite_folder SET name=? WHERE id=? AND owner=?",
          body.name().trim(),
          id,
          user.getName());
    } catch (DuplicateKeyException e) {
      throw ApiError.conflict("已经有同名的收藏夹了，换个名字试试。");
    }
    return Map.of("id", id, "name", body.name().trim());
  }

  @DeleteMapping("/{id}")
  @Transactional
  void delete(Principal user, @PathVariable String id) {
    lockOwned(user.getName(), id);
    db.update(
        "UPDATE vs_favorite SET folder_id=NULL WHERE folder_id=? AND owner=?", id, user.getName());
    db.update("DELETE FROM vs_favorite_folder WHERE id=? AND owner=?", id, user.getName());
  }
}
