package dev.videosearch.task;

import dev.videosearch.api.ApiError;
import dev.videosearch.search.SearchContext;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final SearchContext contexts;

  public TaskController(JdbcTemplate db, TransactionTemplate tx, SearchContext contexts) {
    this.db = db;
    this.tx = tx;
    this.contexts = contexts;
  }

  @GetMapping
  Object list(Principal user) {
    var rows =
        db.queryForList(
            "SELECT"
                + " t.id,t.video_id,t.kind,t.stage,t.status,t.error_message,t.created_at,t.updated_at,t.input_json,v.name"
                + " AS video_name,l.name AS library_name FROM vs_task t LEFT JOIN vs_video v ON"
                + " v.id=t.video_id AND v.owner=t.owner LEFT JOIN vs_library l ON l.id=v.library_id"
                + " AND l.owner=t.owner WHERE t.owner=? AND t.parent_id IS NULL ORDER BY"
                + " t.created_at DESC,t.id LIMIT 50",
            user.getName());
    for (var row : rows) {
      Object input = row.remove("input_json");
      if ("SEARCH".equals(row.get("kind")))
        row.put("context", contexts.describe(user.getName(), input));
    }
    return rows;
  }

  private Map<String, Object> owned(String owner, String id) {
    return db
        .queryForList(
            "SELECT"
                + " id,video_id,kind,stage,status,attempts,cancel_requested,error_message,created_at,updated_at,input_json"
                + " FROM vs_task WHERE id=? AND owner=?",
            id,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(ApiError::missing);
  }

  @GetMapping("/{id}")
  Object get(Principal user, @PathVariable String id) {
    var result = new LinkedHashMap<>(owned(user.getName(), id));
    Object input = result.remove("input_json");
    if ("SEARCH".equals(result.get("kind")))
      result.put("context", contexts.describe(user.getName(), input));
    result.put(
        "children",
        db.queryForList(
            "SELECT id,stage,status,attempts,error_message FROM vs_task WHERE parent_id=? AND"
                + " owner=? ORDER BY created_at,id",
            id,
            user.getName()));
    return result;
  }

  @PostMapping("/{id}/cancel")
  Object cancel(Principal user, @PathVariable String id) {
    owned(user.getName(), id);
    tx.executeWithoutResult(
        status ->
            db.update(
                "UPDATE vs_task SET cancel_requested=TRUE,status=CASE WHEN status IN"
                    + " ('PENDING','RETRY_WAIT','WAITING') THEN 'CANCELLED' ELSE status"
                    + " END,updated_at=CURRENT_TIMESTAMP WHERE (id=? OR parent_id=?) AND owner=?"
                    + " AND status IN ('PENDING','RUNNING','RETRY_WAIT','WAITING')",
                id,
                id,
                user.getName()));
    return owned(user.getName(), id);
  }

  @PostMapping("/{id}/retry")
  Object retry(Principal user, @PathVariable String id) {
    owned(user.getName(), id);
    tx.executeWithoutResult(
        status -> {
          db.update("DELETE FROM vs_agent_checkpoint WHERE task_id=?", id);
          db.update("DELETE FROM vs_failed_task WHERE task_id=?", id);
          var root =
              db.queryForMap(
                  "SELECT * FROM vs_task WHERE id=? AND owner=? FOR UPDATE", id, user.getName());
          if ("SEARCH".equals(root.get("kind"))) {
            var context = contexts.describe(user.getName(), root.get("input_json"));
            contexts.requireReady(
                user.getName(),
                String.valueOf(context.getOrDefault("libraryId", "")),
                context.get("videoIds") instanceof List<?> ids ? ids : List.of());
          }
          if (root.get("kind").equals("PARSE")
              && root.get("parent_id") == null
              && root.get("status").equals("FAILED")
              && db.queryForObject("SELECT COUNT(*) FROM vs_task WHERE parent_id=?", Long.class, id)
                  > 0) {
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE",
                    Long.class,
                    root.get("video_id"),
                    user.getName())
                == 0) throw ApiError.missing();
            db.update(
                "UPDATE vs_task SET"
                    + " status='WAITING',cancel_requested=FALSE,error_message=NULL,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=?",
                id);
            for (var child :
                db.queryForList(
                    "SELECT id FROM vs_task WHERE parent_id=? AND status IN ('FAILED','CANCELLED')",
                    id)) {
              db.update(
                  "UPDATE vs_task SET"
                      + " status='PENDING',attempts=0,cancel_requested=FALSE,execution_id=NULL,lease_until=NULL,error_message=NULL,next_run=CURRENT_TIMESTAMP"
                      + " WHERE id=?",
                  child.get("id"));
              db.update(
                  "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                  UUID.randomUUID().toString(),
                  child.get("id"));
            }
            return;
          }
          if (db.update(
                  "UPDATE vs_task SET"
                      + " status='PENDING',attempts=0,error_message=NULL,execution_id=NULL,lease_until=NULL,output_json=CASE"
                      + " WHEN kind='SEARCH' THEN NULL ELSE output_json"
                      + " END,next_run=CURRENT_TIMESTAMP,cancel_requested=FALSE,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND owner=? AND status='FAILED' AND (video_id IS NULL OR"
                      + " video_id IN (SELECT id FROM vs_video WHERE deleted=FALSE))",
                  id,
                  user.getName())
              != 1) throw ApiError.conflict("仅失败且资源仍有效的任务可以重试");
          db.update(
              "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)", UUID.randomUUID().toString(), id);
        });
    return owned(user.getName(), id);
  }
}
