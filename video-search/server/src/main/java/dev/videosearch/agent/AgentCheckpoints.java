package dev.videosearch.agent;

import dev.videosearch.lab.JsonFiles;
import dev.videosearch.task.PersistentTasks;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL is authoritative. Cache writes happen only after a fenced transaction commits. */
@Service
public class AgentCheckpoints {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectProvider<RedissonClient> redis;

  public AgentCheckpoints(
      JdbcTemplate db, TransactionTemplate tx, ObjectProvider<RedissonClient> redis) {
    this.db = db;
    this.tx = tx;
    this.redis = redis;
  }

  public com.fasterxml.jackson.databind.JsonNode load(PersistentTasks.Lease lease)
      throws Exception {
    Object durable = lease.row().get("output_json");
    if (durable == null) return null;
    String json = durable.toString();
    try {
      var client = redis.getIfAvailable();
      if (client != null) {
        Object saved =
            client
                .getBucket(
                    "framelock:checkpoint:latest:" + lease.id() + ":" + JsonFiles.hash(json),
                    StringCodec.INSTANCE)
                .get();
        if (saved != null && saved.toString().equals(json))
          return JsonFiles.JSON.readTree(saved.toString());
      }
    } catch (RuntimeException ignored) {
    }
    return JsonFiles.JSON.readTree(json);
  }

  public void save(PersistentTasks.Lease lease, String phase, Object state) throws Exception {
    String json = JsonFiles.JSON.writeValueAsString(state);
    boolean committed =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  if (db.queryForList(
                          "SELECT id FROM vs_task WHERE id=? AND execution_id=? AND"
                              + " status='RUNNING' AND cancel_requested=FALSE AND"
                              + " lease_until>CURRENT_TIMESTAMP FOR UPDATE",
                          lease.id(),
                          lease.executionId())
                      .isEmpty()) return false;
                  db.update(
                      "DELETE FROM vs_agent_checkpoint WHERE task_id=? AND phase=?",
                      lease.id(),
                      phase);
                  db.update(
                      "INSERT INTO vs_agent_checkpoint(task_id,phase,execution_id,payload)"
                          + " VALUES(?,?,?,?)",
                      lease.id(),
                      phase,
                      lease.executionId(),
                      json);
                  db.update(
                      "UPDATE vs_task SET output_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                      json,
                      lease.id());
                  return true;
                }));
    if (!committed) throw new IllegalArgumentException("Search cancelled or replaced");
    try {
      var client = redis.getIfAvailable();
      if (client != null) {
        client
            .getBucket("framelock:checkpoint:" + lease.id() + ":" + phase, StringCodec.INSTANCE)
            .set(json, Duration.ofHours(24));
        client
            .getBucket(
                "framelock:checkpoint:latest:" + lease.id() + ":" + JsonFiles.hash(json),
                StringCodec.INSTANCE)
            .set(json, Duration.ofHours(24));
      }
    } catch (RuntimeException ignored) {
      /* committed state remains available */
    }
  }
}
