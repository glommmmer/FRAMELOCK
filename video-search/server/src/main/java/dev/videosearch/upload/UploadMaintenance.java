package dev.videosearch.upload;

import dev.videosearch.lab.JsonFiles;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Schedule cleanup as durable work; never delete objects while holding an upload transaction. */
@Service
public class UploadMaintenance {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public UploadMaintenance(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  @Scheduled(fixedDelay = 60000)
  public void enqueueExpired() {
    for (var row :
        db.queryForList(
            "SELECT id FROM vs_upload u WHERE expires_at<CURRENT_TIMESTAMP AND (status='OPEN' OR"
                + " (status='MERGING' AND lease_until<CURRENT_TIMESTAMP) OR (status IN"
                + " ('COMPLETE','EXPIRED') AND (EXISTS(SELECT 1 FROM vs_upload_part p WHERE"
                + " p.upload_id=u.id) OR EXISTS(SELECT 1 FROM vs_upload_object o WHERE"
                + " o.upload_id=u.id AND o.cleaned=FALSE)))) AND NOT EXISTS(SELECT 1 FROM vs_task t"
                + " WHERE t.idempotency_key LIKE CONCAT('upload-parts:',u.id,':%') AND"
                + " t.status<>'SUCCEEDED') LIMIT 20")) {
      tx.executeWithoutResult(
          status -> {
            String upload = row.get("id").toString();
            var locked =
                db.queryForList(
                    "SELECT owner,status FROM vs_upload WHERE id=? AND expires_at<CURRENT_TIMESTAMP"
                        + " AND (status IN ('OPEN','COMPLETE','EXPIRED') OR (status='MERGING' AND"
                        + " lease_until<CURRENT_TIMESTAMP)) FOR UPDATE",
                    upload);
            if (locked.isEmpty()) return;
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM vs_task WHERE idempotency_key LIKE ? AND"
                        + " status<>'SUCCEEDED'",
                    Long.class,
                    "upload-parts:" + upload + ":%")
                > 0) return;
            if (!locked.getFirst().get("status").equals("COMPLETE"))
              db.update(
                  "UPDATE vs_upload SET status='EXPIRED',lease_until=NULL WHERE id=?", upload);
            String task = UUID.randomUUID().toString();
            try {
              db.update(
                  "INSERT INTO vs_task(id,owner,kind,stage,status,input_json,idempotency_key)"
                      + " VALUES(?,?,'CLEANUP','UPLOAD_CLEANUP','PENDING',?,?)",
                  task,
                  locked.getFirst().get("owner"),
                  JsonFiles.JSON.writeValueAsString(Map.of("uploadId", upload)),
                  "upload-parts:" + upload + ":" + task);
              db.update(
                  "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)",
                  UUID.randomUUID().toString(),
                  task);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
            } catch (java.io.IOException e) {
              throw new IllegalStateException(e);
            }
          });
    }
  }
}
