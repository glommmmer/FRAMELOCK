package dev.videosearch.api;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final JdbcTemplate db;

  @org.springframework.beans.factory.annotation.Value("${app.dispatcher:mq}")
  private String mode = "mq";

  @org.springframework.beans.factory.annotation.Value("${app.storage:oss}")
  private String storage = "oss";

  public HealthController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/health")
  public ResponseEntity<?> check() {
    try {
      if (Integer.valueOf(1).equals(db.queryForObject("SELECT 1", Integer.class)))
        return ResponseEntity.ok(
            Map.of(
                "application",
                "FRAMELOCK",
                "status",
                "UP",
                "database",
                "UP",
                "runtimeMode",
                mode,
                "storage",
                storage));
    } catch (DataAccessException ignored) {
      // Readiness must fail when the database is unavailable; never expose connection details.
    }
    return ResponseEntity.status(503)
        .body(Map.of("application", "FRAMELOCK", "status", "DOWN", "database", "DOWN"));
  }
}
