package dev.videosearch.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.videosearch.agent.AgentCheckpoints;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.search.FollowUpContext;
import dev.videosearch.task.PersistentTasks;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:alignment;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.storage=local",
      "app.data-dir=target/alignment",
      "app.admin-password="
    })
@AutoConfigureMockMvc
class StackAlignmentTest {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired PersistentTasks tasks;
  @Autowired AgentCheckpoints checkpoints;
  @Autowired FollowUpContext followups;

  @BeforeEach
  void setup() throws Exception {
    for (String table :
        List.of(
            "vs_agent_checkpoint",
            "vs_failed_task",
            "vs_outbox",
            "vs_task",
            "vs_video",
            "vs_library")) db.update("DELETE FROM " + table);
    db.update("INSERT INTO vs_library(id,owner,name) VALUES('l','one','demo')");
    db.update(
        "INSERT INTO"
            + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status,active_version,index_execution_id)"
            + " VALUES('v','l','one','x.mp4','key','hash',1,10000,FALSE,'READY','ver','exec')");
    String input =
        JsonFiles.JSON.writeValueAsString(
            Map.of("libraryId", "l", "videoIds", List.of("v"), "query", "喝饮料", "mode", "AGENT"));
    String result =
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "matches",
                List.of(
                    Map.of("videoId", "v", "versionId", "ver", "startMs", 1000, "endMs", 2000))));
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,input_json,output_json,idempotency_key)"
            + " VALUES('parent','one','SEARCH','DONE','SUCCEEDED',?,?,'parent')",
        input,
        result);
  }

  @Test
  void followupKeepsParentScopeAndCannotReadAnotherUser() throws Exception {
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"libraryId\":\"l\",\"query\":\"往前看看\",\"parentSearchId\":\"parent\",\"mode\":\"AGENT\"}"))
        .andExpect(status().isAccepted());
    String input =
        db.queryForObject("SELECT input_json FROM vs_task WHERE id<>'parent'", String.class);
    assertEquals("v", JsonFiles.JSON.readTree(input).path("videoIds").get(0).asText());
    assertThrows(ApiError.class, () -> followups.parent("two", "parent"));
    assertTrue(followups.resolve("one", "parent", "只看第二段").contains("1000"));
  }

  @Test
  void followupRejectsExpandedScopeAndDeletedEvidence() throws Exception {
    mvc.perform(
            post("/api/v1/searches")
                .with(user("one"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"libraryId\":\"l\",\"videoIds\":[\"other\"],\"query\":\"继续\",\"parentSearchId\":\"parent\"}"))
        .andExpect(status().isBadRequest());
    db.update("UPDATE vs_video SET deleted=TRUE WHERE id='v'");
    assertThrows(ApiError.class, () -> followups.resolve("one", "parent", "往前找"));
  }

  @Test
  void checkpointRejectsExpiredExecutionAndSurvivesCacheAbsence() throws Exception {
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,idempotency_key)"
            + " VALUES('work','one','SEARCH','PLAN','PENDING','work')");
    var old = tasks.claim("work").orElseThrow();
    checkpoints.save(old, "PLANNER", Map.of("toolCalls", 1));
    db.update("UPDATE vs_task SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE id='work'");
    tasks.recoverExpired();
    var current = tasks.claim("work").orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoints.save(old, "PLANNER", Map.of("toolCalls", 99)));
    checkpoints.save(current, "CRITIC", Map.of("toolCalls", 2));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_agent_checkpoint WHERE task_id='work'", Integer.class));
    assertTrue(
        db.queryForObject("SELECT output_json FROM vs_task WHERE id='work'", String.class)
            .contains("2"));
  }

  @Test
  void permanentFailureIsRecordedForReplay() {
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,idempotency_key)"
            + " VALUES('work','one','SEARCH','PLAN','PENDING','work')");
    tasks.fail(tasks.claim("work").orElseThrow(), "provider unavailable", true);
    assertEquals(
        "one",
        db.queryForObject("SELECT owner FROM vs_failed_task WHERE task_id='work'", String.class));
  }
}
