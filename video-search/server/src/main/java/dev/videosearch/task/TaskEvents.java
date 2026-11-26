package dev.videosearch.task;

import dev.videosearch.api.ApiError;
import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskEvents {
  private final JdbcTemplate db;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("task-events-", 0).factory());
  private final Map<SseEmitter, Watch> watches = new ConcurrentHashMap<>();

  private record Watch(String id, String owner) {}

  public TaskEvents(JdbcTemplate db) {
    this.db = db;
    timer.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
  }

  @GetMapping(value = "/{id}/events", produces = "text/event-stream")
  SseEmitter events(Principal user, @PathVariable String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_task WHERE id=? AND owner=?", Long.class, id, user.getName())
        == 0) throw ApiError.missing();
    if (watches.size() >= 100) throw ApiError.conflict("任务订阅已满，请使用任务详情刷新");
    var emitter = new SseEmitter(120000L);
    watches.put(emitter, new Watch(id, user.getName()));
    emitter.onCompletion(() -> watches.remove(emitter));
    emitter.onTimeout(
        () -> {
          watches.remove(emitter);
          emitter.complete();
        });
    emitter.onError(e -> watches.remove(emitter));
    return emitter;
  }

  public synchronized void refresh() {
    poll();
  }

  private synchronized void poll() {
    watches.forEach(
        (emitter, watch) -> {
          try {
            var rows =
                db.queryForList(
                    "SELECT id,status,stage,updated_at,error_message FROM vs_task WHERE id=? AND"
                        + " owner=?",
                    watch.id(),
                    watch.owner());
            if (rows.isEmpty()) {
              emitter.complete();
              watches.remove(emitter);
              return;
            }
            var state = rows.getFirst();
            emitter.send(
                SseEmitter.event()
                    .name("state")
                    .id(state.get("updated_at").toString())
                    .data(state));
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state.get("status"))) {
              emitter.complete();
              watches.remove(emitter);
            }
          } catch (Exception e) {
            watches.remove(emitter);
            emitter.completeWithError(e);
          }
        });
  }

  @PreDestroy
  void close() {
    watches.keySet().forEach(SseEmitter::complete);
    timer.shutdownNow();
  }
}
