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
@RequestMapping("/api/v1/libraries")
public class LibraryController {
  private final LibraryMapper mapper;
  private final JdbcTemplate db;

  public LibraryController(LibraryMapper mapper, JdbcTemplate db) {
    this.mapper = mapper;
    this.db = db;
  }

  public record Create(@NotBlank @Size(max = 120) String name) {}

  @PostMapping
  Object create(Principal user, @Valid @RequestBody Create body) {
    var library = new LibraryEntity();
    library.id = UUID.randomUUID().toString();
    library.owner = user.getName();
    library.name = body.name().trim();
    mapper.insert(library);
    return Map.of("id", library.id, "name", library.name);
  }

  @GetMapping
  Object list(Principal user) {
    return db.queryForList(
        "SELECT id,name,archived,created_at FROM vs_library WHERE owner=? AND deleted=FALSE ORDER"
            + " BY created_at DESC,id",
        user.getName());
  }

  public record Visibility(@NotNull Boolean archived) {}

  private void lockOwned(String owner, String id) {
    if (db.queryForList(
            "SELECT id FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE FOR UPDATE",
            id,
            owner)
        .isEmpty()) throw ApiError.missing();
  }

  @PatchMapping("/{id}")
  @Transactional
  Object rename(Principal user, @PathVariable String id, @Valid @RequestBody Create body) {
    lockOwned(user.getName(), id);
    db.update(
        "UPDATE vs_library SET name=? WHERE id=? AND owner=?",
        body.name().trim(),
        id,
        user.getName());
    return Map.of("id", id, "name", body.name().trim());
  }

  @DeleteMapping("/{id}")
  @Transactional
  void delete(Principal user, @PathVariable String id) {
    lockOwned(user.getName(), id);
    long videos =
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_video WHERE library_id=? AND owner=? AND deleted=FALSE",
            Long.class,
            id,
            user.getName());
    long uploads =
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_upload WHERE library_id=? AND owner=? AND status IN"
                + " ('OPEN','MERGING')",
            Long.class,
            id,
            user.getName());
    if (videos > 0 || uploads > 0) throw ApiError.conflict("项目里还有视频或正在上传的文件，请先处理完这些内容再删除项目。");
    db.update("UPDATE vs_library SET deleted=TRUE WHERE id=? AND owner=?", id, user.getName());
  }

  /** Presentation preference only: hiding a project never deletes its videos or tasks. */
  @PatchMapping("/{id}/visibility")
  Object visibility(Principal user, @PathVariable String id, @Valid @RequestBody Visibility body) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE",
            Long.class,
            id,
            user.getName())
        == 0) throw ApiError.missing();
    db.update(
        "UPDATE vs_library SET archived=? WHERE id=? AND owner=?",
        body.archived(),
        id,
        user.getName());
    return Map.of("id", id, "archived", body.archived());
  }

  @GetMapping("/{id}/videos")
  Object videos(Principal user, @PathVariable String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE",
            Long.class,
            id,
            user.getName())
        == 0) throw ApiError.missing();
    return db.queryForList(
        "SELECT id,name,file_size,duration_ms,status,active_version,created_at FROM vs_video WHERE"
            + " owner=? AND library_id=? AND deleted=FALSE ORDER BY created_at DESC,id",
        user.getName(),
        id);
  }
}
