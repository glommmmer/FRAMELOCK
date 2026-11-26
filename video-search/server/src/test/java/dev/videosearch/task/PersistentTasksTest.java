package dev.videosearch.task;

import static org.junit.jupiter.api.Assertions.*;

import dev.videosearch.VideoSearchApplication;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = VideoSearchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.storage=local",
      "app.data-dir=target/task-tests",
      "app.admin-password="
    })
@org.springframework.test.context.TestExecutionListeners(
    inheritListeners = false,
    listeners = {
      org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class
    })
class PersistentTasksTest {
  @Autowired JdbcTemplate db;
  @Autowired PersistentTasks tasks;

  @BeforeEach
  void prepare() {
    db.update("DELETE FROM vs_outbox");
    db.update("DELETE FROM vs_task");
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,idempotency_key)"
            + " VALUES('t','one','PARSE','VISUAL','PENDING','t')");
  }

  @Test
  void duplicateDeliveryCannotClaimRunningOrFinishedTask() {
    var lease = tasks.claim("t").orElseThrow();
    assertTrue(tasks.claim("t").isEmpty());
    assertTrue(tasks.succeed(lease, Map.of("done", true), () -> {}));
    assertTrue(tasks.claim("t").isEmpty());
  }

  @Test
  void oldWorkerCannotCommitAfterRecovery() {
    var old = tasks.claim("t").orElseThrow();
    db.update("UPDATE vs_task SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE id='t'");
    assertEquals(1, tasks.recoverExpired());
    var replacement = tasks.claim("t").orElseThrow();
    assertFalse(tasks.succeed(old, Map.of(), () -> fail("old worker wrote data")));
    assertTrue(tasks.succeed(replacement, Map.of(), () -> {}));
  }

  @Test
  void cancellationPreventsHeartbeatAndResultCommit() {
    var lease = tasks.claim("t").orElseThrow();
    db.update("UPDATE vs_task SET cancel_requested=TRUE WHERE id='t'");
    assertFalse(tasks.heartbeat(lease));
    assertFalse(tasks.succeed(lease, Map.of(), () -> fail("cancelled task wrote data")));
    tasks.fail(lease, "cancelled", false);
    assertEquals(
        "CANCELLED", db.queryForObject("SELECT status FROM vs_task WHERE id='t'", String.class));
  }

  @Test
  void failedTemporaryAttemptCreatesDurableRetry() {
    var lease = tasks.claim("t").orElseThrow();
    tasks.fail(lease, "provider timeout", false);
    assertEquals(
        "RETRY_WAIT", db.queryForObject("SELECT status FROM vs_task WHERE id='t'", String.class));
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM vs_outbox WHERE task_id='t'", Integer.class));
  }

  @Test
  void expiredLeaseCannotBeRevivedOrScheduleADeferredStage() {
    var old = tasks.claim("t").orElseThrow();
    db.update("UPDATE vs_task SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE id='t'");
    assertFalse(tasks.heartbeat(old));
    tasks.defer(old, "ASR_POLL", Map.of("jobId", "old"), 0);
    assertEquals(
        "RUNNING", db.queryForObject("SELECT status FROM vs_task WHERE id='t'", String.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM vs_outbox", Integer.class));
    assertEquals(1, tasks.recoverExpired());
    assertTrue(tasks.claim("t").isPresent());
  }
}
