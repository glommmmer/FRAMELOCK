package dev.videosearch.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
  @Test
  void unavailableDatabaseDoesNotReportReadyOrExposeCredentials() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new DataAccessResourceFailureException("private connection details"));
    var response = new HealthController(db).check();
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertTrue(response.getBody().toString().contains("DOWN"));
    assertFalse(response.getBody().toString().contains("private connection details"));
  }
}
