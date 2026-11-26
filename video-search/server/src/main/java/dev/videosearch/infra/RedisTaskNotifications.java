package dev.videosearch.infra;

import dev.videosearch.task.TaskEvents;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Notifications only wake readers; all SSE payloads still come from an authorized DB query. */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisTaskNotifications {
  private final JdbcTemplate db;
  private final RTopic topic;
  private final int listener;
  private Timestamp cursor = new Timestamp(System.currentTimeMillis() - 1000);

  public RedisTaskNotifications(JdbcTemplate db, RedissonClient redis, TaskEvents events) {
    this.db = db;
    topic = redis.getTopic("framelock:task-events", StringCodec.INSTANCE);
    listener = topic.addListener(String.class, (channel, id) -> events.refresh());
  }

  @Scheduled(fixedDelay = 1000)
  public void publish() {
    try {
      var rows =
          db.queryForList(
              "SELECT id,updated_at FROM vs_task WHERE updated_at>? ORDER BY updated_at,id LIMIT"
                  + " 100",
              cursor);
      for (var row : rows) {
        topic.publish(row.get("id").toString());
        cursor = (Timestamp) row.get("updated_at");
      }
    } catch (RuntimeException ignored) {
      /* SSE timer remains available during Redis outage */
    }
  }

  @PreDestroy
  void close() {
    try {
      topic.removeListener(listener);
    } catch (RuntimeException ignored) {
    }
  }
}
