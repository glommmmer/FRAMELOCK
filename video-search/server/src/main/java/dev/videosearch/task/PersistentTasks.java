package dev.videosearch.task;

import dev.videosearch.lab.JsonFiles;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** DB state is authoritative; MQ delivery and cache presence are not task completion. */
@Service
public class PersistentTasks {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public PersistentTasks(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public record Lease(String id, String executionId, Map<String, Object> row) {}

  public Optional<Lease> claim(String id) {
    String execution = UUID.randomUUID().toString();
    return tx.execute(
        status -> {
          int changed =
              db.update(
                  "UPDATE vs_task SET"
                      + " status='RUNNING',execution_id=?,attempts=attempts+1,lease_until=?,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status IN ('PENDING','RETRY_WAIT') AND"
                      + " next_run<=CURRENT_TIMESTAMP AND cancel_requested=FALSE",
                  execution,
                  Timestamp.from(Instant.now().plusSeconds(120)),
                  id);
          if (changed != 1) return Optional.empty();
          return Optional.of(
              new Lease(id, execution, db.queryForMap("SELECT * FROM vs_task WHERE id=?", id)));
        });
  }

  public boolean heartbeat(Lease lease) {
    return db.update(
            "UPDATE vs_task SET lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND"
                + " execution_id=? AND status='RUNNING' AND cancel_requested=FALSE AND"
                + " lease_until>CURRENT_TIMESTAMP",
            Timestamp.from(Instant.now().plusSeconds(120)),
            lease.id(),
            lease.executionId())
        == 1;
  }

  public boolean active(Lease lease) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM vs_task WHERE id=? AND execution_id=? AND status='RUNNING' AND"
                + " cancel_requested=FALSE AND lease_until>CURRENT_TIMESTAMP",
            Long.class,
            lease.id(),
            lease.executionId())
        == 1;
  }

  public boolean succeed(Lease lease, Object output, Runnable commit) {
    return finish(lease, "SUCCEEDED", output, commit);
  }

  public boolean awaitChildren(Lease lease, Runnable commit) {
    return finish(lease, "WAITING", Map.of("waitingForChildren", true), commit);
  }

  private boolean finish(Lease lease, String state, Object output, Runnable commit) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var locked =
                  db.queryForList(
                      "SELECT id FROM vs_task WHERE id=? AND execution_id=? AND status='RUNNING'"
                          + " AND cancel_requested=FALSE AND lease_until>CURRENT_TIMESTAMP FOR"
                          + " UPDATE",
                      lease.id(),
                      lease.executionId());
              if (locked.isEmpty()) return false;
              commit.run();
              try {
                db.update(
                    "UPDATE vs_task SET"
                        + " status=?,output_json=?,lease_until=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND execution_id=?",
                    state,
                    JsonFiles.JSON.writeValueAsString(output),
                    lease.id(),
                    lease.executionId());
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
              return true;
            }));
  }

  public void defer(Lease lease, String stage, Object input, int delaySeconds) {
    tx.executeWithoutResult(
        status -> {
          try {
            if (db.update(
                    "UPDATE vs_task SET"
                        + " status='RETRY_WAIT',stage=?,input_json=?,attempts=0,lease_until=NULL,next_run=?,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND execution_id=? AND status='RUNNING' AND"
                        + " cancel_requested=FALSE AND lease_until>CURRENT_TIMESTAMP",
                    stage,
                    JsonFiles.JSON.writeValueAsString(input),
                    Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                    lease.id(),
                    lease.executionId())
                == 1)
              db.update(
                  "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                  UUID.randomUUID().toString(),
                  lease.id());
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  public void fail(Lease lease, String message, boolean permanent) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              db.queryForList(
                  "SELECT attempts,cancel_requested FROM vs_task WHERE id=? AND execution_id=? AND"
                      + " status='RUNNING' AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",
                  lease.id(),
                  lease.executionId());
          if (rows.isEmpty()) return;
          boolean cancelled = Boolean.TRUE.equals(rows.getFirst().get("cancel_requested"));
          int attempts = ((Number) rows.getFirst().get("attempts")).intValue();
          String next =
              cancelled ? "CANCELLED" : permanent || attempts >= 3 ? "FAILED" : "RETRY_WAIT";
          db.update(
              "UPDATE vs_task SET"
                  + " status=?,lease_until=NULL,error_message=?,next_run=?,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=? AND execution_id=?",
              next,
              message.substring(0, Math.min(1000, message.length())),
              Timestamp.from(Instant.now().plusSeconds(5L * (1L << Math.min(attempts, 5)))),
              lease.id(),
              lease.executionId());
          if (next.equals("FAILED")) {
            db.update("DELETE FROM vs_failed_task WHERE task_id=?", lease.id());
            db.update(
                "INSERT INTO vs_failed_task(task_id,owner,execution_id,reason) VALUES(?,?,?,?)",
                lease.id(),
                lease.row().get("owner"),
                lease.executionId(),
                message.substring(0, Math.min(1000, message.length())));
          }
          if (next.equals("RETRY_WAIT"))
            db.update(
                "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                UUID.randomUUID().toString(),
                lease.id());
        });
  }

  public int recoverExpired() {
    return tx.execute(
        status -> {
          var rows =
              db.queryForList(
                  "SELECT id,owner,execution_id,attempts,cancel_requested FROM vs_task WHERE"
                      + " status='RUNNING' AND lease_until<CURRENT_TIMESTAMP FOR UPDATE");
          for (var row : rows) {
            String id = (String) row.get("id");
            boolean cancel = Boolean.TRUE.equals(row.get("cancel_requested"));
            boolean exhausted = ((Number) row.get("attempts")).intValue() >= 3;
            String next = cancel ? "CANCELLED" : exhausted ? "FAILED" : "PENDING";
            db.update(
                "UPDATE vs_task SET"
                    + " status=?,execution_id=NULL,lease_until=NULL,next_run=CURRENT_TIMESTAMP,error_message='Worker"
                    + " lease expired',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                next,
                id);
            if (next.equals("FAILED")) {
              db.update("DELETE FROM vs_failed_task WHERE task_id=?", id);
              db.update(
                  "INSERT INTO vs_failed_task(task_id,owner,execution_id,reason) VALUES(?,?,?,?)",
                  id,
                  row.get("owner"),
                  row.get("execution_id"),
                  "Worker lease expired after maximum attempts");
            }
            if (next.equals("PENDING"))
              db.update(
                  "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                  UUID.randomUUID().toString(),
                  id);
          }
          return rows.size();
        });
  }
}
