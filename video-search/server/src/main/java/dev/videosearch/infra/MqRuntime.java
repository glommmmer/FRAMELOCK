package dev.videosearch.infra;

import dev.videosearch.task.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
@Import(org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration.class)
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class MqRuntime {
  public static final String TOPIC = "video-search-stages-v1";
  private final RocketMQTemplate mq;
  private final JdbcTemplate db;
  private final PersistentTasks tasks;
  private final String topic, failureTopic;

  public MqRuntime(
      RocketMQTemplate mq,
      JdbcTemplate db,
      PersistentTasks tasks,
      @org.springframework.beans.factory.annotation.Value("${app.mq.topic:video-search-stages-v1}")
          String topic,
      @org.springframework.beans.factory.annotation.Value(
              "${app.mq.failure-topic:framelock-stage-failures-v1}")
          String failureTopic) {
    this.mq = mq;
    this.db = db;
    this.tasks = tasks;
    this.topic = topic;
    this.failureTopic = failureTopic;
  }

  @Scheduled(fixedDelay = 1000)
  public void publish() {
    for (var row :
        db.queryForList(
            "SELECT o.id,o.task_id FROM vs_outbox o JOIN vs_task t ON t.id=o.task_id WHERE"
                + " o.status='PENDING' AND t.status IN ('PENDING','RETRY_WAIT') AND"
                + " t.next_run<=CURRENT_TIMESTAMP AND t.cancel_requested=FALSE ORDER BY"
                + " o.created_at LIMIT 10")) {
      try {
        mq.syncSend(topic, row.get("task_id").toString());
        db.update(
            "UPDATE vs_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP WHERE id=? AND"
                + " status='PENDING'",
            row.get("id"));
      } catch (Exception error) {
        break;
      }
    }
    for (var row :
        db.queryForList(
            "SELECT task_id,execution_id FROM vs_failed_task WHERE published=FALSE LIMIT 10")) {
      try {
        mq.syncSend(failureTopic, row.get("task_id").toString());
        db.update(
            "UPDATE vs_failed_task SET published=TRUE WHERE task_id=? AND execution_id=?",
            row.get("task_id"),
            row.get("execution_id"));
      } catch (Exception error) {
        break;
      }
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void recover() {
    tasks.recoverExpired();
  }

  @Component
  @ConditionalOnProperty(
      name = {"app.worker.enabled", "app.mq.enabled"},
      havingValue = "true")
  @RocketMQMessageListener(
      topic = "${app.mq.topic:video-search-stages-v1}",
      consumerGroup = "${app.mq.consumer-group:video-search-worker-v1}",
      consumeThreadNumber = 2,
      maxReconsumeTimes = 3)
  public static class Consumer implements RocketMQListener<String> {
    private final PersistentTasks tasks;
    private final ObjectProvider<StageProcessor> stages;
    private final ScheduledExecutorService heartbeats =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("mq-heartbeat-", 0).factory());

    public Consumer(PersistentTasks tasks, ObjectProvider<StageProcessor> stages) {
      this.tasks = tasks;
      this.stages = stages;
    }

    @Override
    public void onMessage(String id) {
      if (!id.matches("[a-f0-9-]{36}")) return;
      var claimed = tasks.claim(id);
      if (claimed.isEmpty()) return;
      var lease = claimed.get();
      Thread thread = Thread.currentThread();
      var heartbeat =
          heartbeats.scheduleAtFixedRate(
              () -> {
                try {
                  if (!tasks.heartbeat(lease)) thread.interrupt();
                } catch (Exception e) {
                  thread.interrupt();
                }
              },
              10,
              10,
              TimeUnit.SECONDS);
      try {
        var processor = stages.getIfAvailable();
        if (processor == null) throw new IllegalStateException("Stage processor unavailable");
        processor.process(lease);
      } catch (Exception error) {
        tasks.fail(
            lease,
            TaskErrors.describe(error),
            error instanceof IllegalArgumentException
                || error.getMessage() != null && error.getMessage().contains("BUDGET_STOP"));
      } finally {
        heartbeat.cancel(false);
        Thread.interrupted();
      }
    }

    @PreDestroy
    void close() {
      heartbeats.shutdownNow();
    }
  }
}
