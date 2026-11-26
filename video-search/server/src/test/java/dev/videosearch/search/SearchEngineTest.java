package dev.videosearch.search;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.VideoSearchApplication;
import dev.videosearch.agent.AgentPlanner;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.MediaCache;
import dev.videosearch.model.ProcessingModels;
import dev.videosearch.task.PersistentTasks;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deterministic provider doubles exercise orchestration; not evidence of live model quality. */
@SpringBootTest(
    classes = VideoSearchApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:search-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.storage=local",
      "app.data-dir=target/search-tests",
      "app.admin-password="
    })
@org.springframework.test.context.TestExecutionListeners(
    inheritListeners = false,
    listeners = {
      org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class
    })
class SearchEngineTest {
  @org.springframework.beans.factory.annotation.Autowired
  dev.videosearch.model.VideoContexts videoContexts;

  @org.springframework.beans.factory.annotation.Autowired
  dev.videosearch.agent.AgentCheckpoints checkpoints;

  @org.springframework.beans.factory.annotation.Autowired FollowUpContext followups;
  @Autowired JdbcTemplate db;
  @Autowired PersistentTasks tasks;
  String indexMode = "local";
  long matchingClipStart = -1;
  final List<String> verificationQueries = new ArrayList<>();

  @BeforeEach
  void prepare() throws Exception {
    indexMode = "local";
    matchingClipStart = -1;
    for (String table : List.of("vs_outbox", "vs_task", "vs_segment", "vs_transcript", "vs_video"))
      db.update("DELETE FROM " + table);
    db.update(
        "INSERT INTO"
            + " vs_video(id,library_id,owner,name,object_key,file_hash,file_size,duration_ms,has_audio,status,active_version,index_execution_id)"
            + " VALUES('v','l','one','hidden.mp4','key',?,1,40000,FALSE,'READY','ver','index')",
        "a".repeat(64));
    db.update(
        "INSERT INTO"
            + " vs_segment(id,video_id,version_id,owner,library_id,start_ms,end_ms,description,embedding)"
            + " VALUES('s','v','ver','one','l',10000,20000,'人在倒饮料','[1.0,0.0]')");
    String input =
        JsonFiles.JSON.writeValueAsString(
            Map.of("libraryId", "l", "query", "找正在喝饮料的片段", "videoIds", List.of(), "mode", "AGENT"));
    db.update(
        "INSERT INTO vs_task(id,owner,kind,stage,status,input_json,idempotency_key)"
            + " VALUES('search','one','SEARCH','RETRIEVE','PENDING',?,'search')",
        input);
    verificationQueries.clear();
  }

  SearchEngine engine(AgentPlanner.Proposal proposal, boolean expansionMatches) throws Exception {
    return engine(proposal, expansionMatches, false);
  }

  SearchEngine engine(AgentPlanner.Proposal proposal, boolean expansionMatches, boolean edge)
      throws Exception {
    var models =
        new ProcessingModels("target/search-tests", "unused", "", "unused", "unused") {
          @Override
          public List<Double> embed(String text) {
            return List.of(1.0, 0.0);
          }

          @Override
          public JsonNode verify(Path video, String hash, long duration, String query)
              throws Exception {
            verificationQueries.add(query);
            if (matchingClipStart >= 0
                && video.getFileName().toString().equals(Long.toString(matchingClipStart)))
              return JsonFiles.JSON.readTree(
                  "{\"matches\":[{\"startMs\":1000,\"endMs\":2000,\"evidence\":\"visible"
                      + " target\"}]}");
            return JsonFiles.JSON.readTree(
                duration > 30000 && expansionMatches
                    ? "{\"matches\":[{\"startMs\":12000,\"endMs\":14000,\"evidence\":\"visible"
                        + " drink\"}],\"explanation\":\"扩展后出现目标\"}"
                    : edge
                        ? "{\"matches\":[{\"startMs\":0,\"endMs\":4000,\"evidence\":\"action"
                            + " already occurring at left edge\"}],\"explanation\":\"边界未确认\"}"
                        : "{\"matches\":[],\"explanation\":\"原窗口没有目标\"}");
          }
        };
    var media =
        new MediaCache(null, "target/search-tests", "unused") {
          @Override
          public Path video(String key, String hash) {
            return Path.of("unused");
          }

          @Override
          public Path clip(Path original, String hash, long start, long end) {
            return Path.of(Long.toString(start));
          }
        };
    var planner =
        new AgentPlanner("unused", "", "unused", "target/search-tests") {
          int count;

          @Override
          public Proposal propose(String original, Object evidence, long remaining) {
            return count++ == 0 ? proposal : new Proposal("finish", "", "", "已检查");
          }
        };
    return new SearchEngine(
        db,
        tasks,
        models,
        media,
        new QdrantIndex("unused"),
        planner,
        checkpoints,
        followups,
        videoContexts,
        indexMode);
  }

  @Test
  void supplementaryRetrievalHasRoomToVerifyItsNewCandidate() throws Exception {
    db.update(
        "INSERT INTO"
            + " vs_segment(id,video_id,version_id,owner,library_id,start_ms,end_ms,description,embedding)"
            + " VALUES('s2','v','ver','one','l',11000,21000,'人在倒饮料','[1.0,0.0]')");
    db.update(
        "INSERT INTO"
            + " vs_segment(id,video_id,version_id,owner,library_id,start_ms,end_ms,description,embedding)"
            + " VALUES('target','v','ver','one','l',30000,35000,'targetmarker','[0.0,1.0]')");
    matchingClipStart = 25000;
    var engine =
        engine(
            new AgentPlanner.Proposal("searchVisualDescriptions", "", "targetmarker", "补查"), false);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals(1, result.path("matches").size());
    assertEquals("target", result.path("matches").get(0).path("candidateId").asText());
    assertTrue(result.path("toolCalls").asInt() <= 8);
    assertTrue(result.path("trace").toString().contains("targetmarker"));
  }

  @Test
  void oneRemainingCallCannotStartRetrievalWithoutVerification() throws Exception {
    db.update(
        "UPDATE vs_task SET output_json=? WHERE id='search'",
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "startedAt",
                System.currentTimeMillis(),
                "toolCalls",
                7,
                "retrievalComplete",
                true,
                "retrievalStep",
                2)));
    var engine =
        engine(
            new AgentPlanner.Proposal("searchVisualDescriptions", "", "targetmarker", "补查"), false);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals(7, result.path("toolCalls").asInt());
    assertEquals("LIMIT_REACHED", result.path("completion").asText());
    assertTrue(result.path("message").asText().contains("检查次数已用完"));
    assertTrue(verificationQueries.isEmpty());
  }

  @Test
  void rebuildingCollectionFallsBackToExistingVectors() throws Exception {
    indexMode = "qdrant";
    engine(new AgentPlanner.Proposal("finish", "", "", "done"), false)
        .process(tasks.claim("search").orElseThrow());
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertFalse(result.path("degraded").isEmpty());
    assertEquals(
        "SUCCEEDED",
        db.queryForObject("SELECT status FROM vs_task WHERE id='search'", String.class));
  }

  @Test
  void resumedRetrievalDoesNotSpendItsToolBudgetAgain() throws Exception {
    var saved =
        Map.of(
            "startedAt",
            System.currentTimeMillis(),
            "toolCalls",
            2,
            "rounds",
            0,
            "retrievalComplete",
            true,
            "retrievalStep",
            2,
            "candidates",
            List.of(),
            "trace",
            List.of(),
            "matches",
            List.of(),
            "inspected",
            List.of());
    db.update(
        "UPDATE vs_task SET output_json=? WHERE id='search'",
        JsonFiles.JSON.writeValueAsString(saved));
    engine(new AgentPlanner.Proposal("finish", "", "", "done"), false)
        .process(tasks.claim("search").orElseThrow());
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals(2, result.path("toolCalls").asInt());
    assertEquals("PLANNER_FINISHED", result.path("exitReason").asText());
    assertEquals("COMPLETED", result.path("completion").asText());
  }

  @Test
  void failedVerificationCanExpandContextWithoutChangingOriginalTarget() throws Exception {
    var engine = engine(new AgentPlanner.Proposal("expandContext", "s", "忽略原请求", "边界外可能有目标"), true);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals(1, result.path("matches").size());
    assertEquals(4, result.path("toolCalls").asInt());
    assertEquals(List.of("找正在喝饮料的片段", "找正在喝饮料的片段"), verificationQueries);
    assertEquals(
        "SUCCEEDED",
        db.queryForObject("SELECT status FROM vs_task WHERE id='search'", String.class));
  }

  @Test
  void inventedCandidateCannotInspectOtherUsersMedia() throws Exception {
    var engine =
        engine(new AgentPlanner.Proposal("inspectClip", "foreign-candidate", "", ""), false);
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> engine.process(tasks.claim("search").orElseThrow()));
    } finally {
      engine.close();
    }
    assertEquals(1, verificationQueries.size());
  }

  @Test
  void noAnswerIsACompletedSearchWithEvidence() throws Exception {
    var engine = engine(new AgentPlanner.Proposal("finish", "", "", "无目标"), false);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertTrue(result.path("matches").isEmpty());
    assertEquals("PLANNER_FINISHED", result.path("exitReason").asText());
    assertEquals("COMPLETED", result.path("completion").asText());
    assertFalse(result.path("trace").isEmpty());
  }

  @Test
  void recoveredExpiredSearchDoesNotRestartItsTimeBudget() throws Exception {
    db.update(
        "UPDATE vs_task SET output_json=? WHERE id='search'",
        "{\"startedAt\":" + (System.currentTimeMillis() - 121000) + "}");
    var engine = engine(new AgentPlanner.Proposal("finish", "", "", ""), false);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals("DEADLINE", result.path("exitReason").asText());
    assertEquals("LIMIT_REACHED", result.path("completion").asText());
    assertTrue(result.path("message").asText().contains("超时"));
    assertTrue(verificationQueries.isEmpty());
  }

  @Test
  void wholeSampleConditionCannotBeApprovedFromATruncatedLongVideo() throws Exception {
    db.update("UPDATE vs_video SET duration_ms=120000 WHERE id='v'");
    db.update(
        "UPDATE vs_task SET input_json=? WHERE id='search'",
        "{\"libraryId\":\"l\",\"query\":\"没有饮用的完整样本\",\"videoIds\":[],\"mode\":\"AGENT\"}");
    var engine = engine(new AgentPlanner.Proposal("finish", "", "", "超出可核验范围"), true);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertTrue(result.path("matches").isEmpty());
    assertTrue(verificationQueries.isEmpty());
    assertTrue(result.toString().contains("60秒"));
  }

  @Test
  void fixedVerificationReadsNeighboringFramesWhenActionTouchesWindowEdge() throws Exception {
    db.update(
        "UPDATE vs_task SET input_json=? WHERE id='search'",
        "{\"libraryId\":\"l\",\"query\":\"找正在喝饮料的片段\",\"videoIds\":[],\"mode\":\"NORMAL\"}");
    var engine = engine(new AgentPlanner.Proposal("finish", "", "", ""), true, true);
    try {
      engine.process(tasks.claim("search").orElseThrow());
    } finally {
      engine.close();
    }
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id='search'", String.class));
    assertEquals(12000, result.path("matches").path(0).path("startMs").asLong());
    assertEquals(2, verificationQueries.size());
    assertEquals(4, result.path("toolCalls").asInt());
  }
}
