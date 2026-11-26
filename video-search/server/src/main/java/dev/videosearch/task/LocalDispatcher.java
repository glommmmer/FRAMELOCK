package dev.videosearch.task;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;

/** Explicit reduced-resource mode. Does not claim to exercise RocketMQ delivery. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.dispatcher", havingValue = "local")
public class LocalDispatcher {
  private final JdbcTemplate db;
  private final PersistentTasks tasks;
  private final StageProcessor stages;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("local-worker-", 0).factory());
  private final java.util.concurrent.atomic.AtomicBoolean busy =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final ScheduledExecutorService heartbeat =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("local-heartbeat-", 0).factory());

  public LocalDispatcher(JdbcTemplate db, PersistentTasks tasks, StageProcessor stages) {
    this.db = db;
    this.tasks = tasks;
    this.stages = stages;
  }

  @Scheduled(fixedDelay = 1000)
  public void dispatch() {
    if (!busy.compareAndSet(false, true)) return;
    try {
      worker.submit(
          () -> {
            try {
              var rows =
                  db.queryForList(
                      "SELECT t.id FROM vs_task t WHERE t.status IN ('PENDING','RETRY_WAIT') AND"
                          + " t.next_run<=CURRENT_TIMESTAMP AND t.cancel_requested=FALSE AND"
                          + " EXISTS(SELECT 1 FROM vs_outbox o WHERE o.task_id=t.id AND"
                          + " o.status='PENDING') ORDER BY t.created_at LIMIT 1");
              if (rows.isEmpty()) return;
              String id = rows.getFirst().get("id").toString();
              var deliveries =
                  db.queryForList(
                      "SELECT id FROM vs_outbox WHERE task_id=? AND status='PENDING'", id);
              var claim = tasks.claim(id);
              if (claim.isEmpty()) return;
              var lease = claim.get();
              Thread executing = Thread.currentThread();
              var beat =
                  heartbeat.scheduleAtFixedRate(
                      () -> {
                        if (!tasks.heartbeat(lease)) executing.interrupt();
                      },
                      10,
                      10,
                      TimeUnit.SECONDS);
              try {
                stages.process(lease);
              } catch (Exception e) {
                tasks.fail(
                    lease,
                    TaskErrors.describe(e),
                    e instanceof IllegalArgumentException
                        || e.getMessage() != null && e.getMessage().contains("BUDGET_STOP"));
              } finally {
                beat.cancel(false);
                Thread.interrupted();
              }
              // Mark only deliveries present before processing; defer() may have added the next
              // delivery.
              for (var delivery : deliveries)
                db.update(
                    "UPDATE vs_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP WHERE id=? AND"
                        + " status='PENDING'",
                    delivery.get("id"));
            } finally {
              busy.set(false);
            }
          });
    } catch (RuntimeException e) {
      busy.set(false);
      throw e;
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void recover() {
    tasks.recoverExpired();
  }

  @PreDestroy
  void close() {
    worker.shutdownNow();
    heartbeat.shutdownNow();
  }
}
