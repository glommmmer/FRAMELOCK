package dev.videosearch.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.agent.AgentPlanner;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.model.*;
import dev.videosearch.upload.UploadService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** Real MySQL/MinIO/Redis/MQ/Qdrant and FFmpeg. Models are explicit zero-cost substitutes. */
@EnabledIfSystemProperty(named = "framelock.pipeline", matches = "true")
@SpringBootTest(
    properties = {
      "app.mq.enabled=true",
      "app.mq.topic=framelock-integration-stages-v1",
      "app.mq.consumer-group=framelock-integration-worker-v1",
      "app.mq.failure-topic=framelock-integration-failures-v1",
      "rocketmq.producer.group=framelock-integration-outbox-v1",
      "app.redis.enabled=true",
      "app.worker.enabled=true",
      "app.dispatcher=mq",
      "app.index-mode=qdrant",
      "app.storage=minio",
      "app.data-dir=target/full-pipeline",
      "app.admin-password="
    })
@AutoConfigureMockMvc
@Import(FullPipelineIntegrationTest.Models.class)
class FullPipelineIntegrationTest {
  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) throws Exception {
    Map<String, String> env = ComponentTestSettings.load();
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:mysql://localhost:3307/framelock_alignment?serverTimezone=UTC");
    registry.add("spring.datasource.username", () -> "video_search");
    registry.add("spring.datasource.password", () -> env.get("VS_DB_PASSWORD"));
    registry.add("VS_REDIS_PASSWORD", () -> env.get("VS_REDIS_PASSWORD"));
    registry.add("app.minio.access-key", () -> env.get("VS_MINIO_ACCESS_KEY"));
    registry.add("app.minio.secret-key", () -> env.get("VS_MINIO_SECRET_KEY"));
    registry.add("app.minio.bucket", () -> "framelock-pipeline-tests");
  }

  @Autowired UploadService uploads;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired org.apache.rocketmq.spring.core.RocketMQTemplate mq;
  @Autowired org.redisson.api.RedissonClient redis;

  @Test
  void uploadParseSearchFollowupAndDeleteThroughRealComponents() throws Exception {
    String owner = "integration-" + UUID.randomUUID(), library = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO vs_library(id,owner,name) VALUES(?,?,?)",
        library,
        owner,
        "integration fixture");
    Path synthetic = Files.createTempFile(Path.of("target"), "pipeline-synthetic-", ".mp4");
    byte[] bytes;
    try {
      Process ffmpeg =
          new ProcessBuilder(
                  System.getenv("FFMPEG_PATH"),
                  "-hide_banner",
                  "-nostdin",
                  "-y",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=size=320x180:rate=10:duration=6",
                  "-an",
                  "-c:v",
                  "libx264",
                  "-pix_fmt",
                  "yuv420p",
                  synthetic.toString())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        assertTrue(ffmpeg.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, ffmpeg.exitValue());
      } finally {
        if (ffmpeg.isAlive()) ffmpeg.destroyForcibly();
      }
      bytes = Files.readAllBytes(synthetic);
    } finally {
      Files.deleteIfExists(synthetic);
    }
    var upload =
        (Map<?, ?>)
            uploads.initialize(
                owner,
                new UploadService.Initialize(
                    library, "fixture.mp4", bytes.length, JsonFiles.hash(bytes)));
    String uploadId = upload.get("id").toString();
    for (int at = 0, part = 0; at < bytes.length; at += UploadService.PART_BYTES, part++) {
      byte[] chunk =
          Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + UploadService.PART_BYTES));
      uploads.part(
          owner, uploadId, part, new java.io.ByteArrayInputStream(chunk), JsonFiles.hash(chunk));
    }
    var receipt = (Map<?, ?>) uploads.complete(owner, uploadId);
    String video = receipt.get("videoId").toString();
    await(receipt.get("taskId").toString());
    assertTrue(
        db.queryForObject("SELECT object_key FROM vs_video WHERE id=?", String.class, video)
            .startsWith("minio:"));
    mvc.perform(
            get("/api/v1/videos/" + video + "/content")
                .header("Range", "bytes=0-31")
                .with(user(owner)))
        .andExpect(status().isPartialContent());
    String request =
        JsonFiles.JSON.writeValueAsString(
            Map.of("libraryId", library, "query", "喝饮料", "mode", "AGENT"));
    var created =
        mvc.perform(
                post("/api/v1/searches")
                    .with(user(owner))
                    .with(csrf())
                    .contentType("application/json")
                    .content(request))
            .andExpect(status().isAccepted())
            .andReturn();
    String search =
        JsonFiles.JSON.readTree(created.getResponse().getContentAsString()).path("id").asText();
    await(search);
    var result =
        JsonFiles.JSON.readTree(
            db.queryForObject("SELECT output_json FROM vs_task WHERE id=?", String.class, search));
    assertFalse(result.path("matches").isEmpty());
    mq.syncSend(
        "framelock-integration-stages-v1",
        search); // duplicate terminal delivery must not execute again
    assertEquals(
        1, db.queryForObject("SELECT attempts FROM vs_task WHERE id=?", Integer.class, search));
    assertNotNull(
        redis
            .getBucket(
                "framelock:followup:" + owner + ":" + search,
                org.redisson.client.codec.StringCodec.INSTANCE)
            .get());
    String followup =
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "libraryId",
                library,
                "query",
                "只看刚才那段",
                "mode",
                "AGENT",
                "parentSearchId",
                search));
    var next =
        mvc.perform(
                post("/api/v1/searches")
                    .with(user(owner))
                    .with(csrf())
                    .contentType("application/json")
                    .content(followup))
            .andExpect(status().isAccepted())
            .andReturn();
    await(JsonFiles.JSON.readTree(next.getResponse().getContentAsString()).path("id").asText());
    mvc.perform(delete("/api/v1/videos/" + video).with(user(owner)).with(csrf()))
        .andExpect(status().isAccepted());
    mvc.perform(get("/api/v1/videos/" + video + "/content").with(user(owner)))
        .andExpect(status().isNotFound());
    long until = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < until
        && db.queryForObject(
                "SELECT COUNT(*) FROM vs_task WHERE video_id=? AND stage='CLEANUP' AND"
                    + " status='SUCCEEDED'",
                Integer.class,
                video)
            == 0) Thread.sleep(200);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM vs_task WHERE video_id=? AND stage='CLEANUP' AND"
                + " status='SUCCEEDED'",
            Integer.class,
            video));
  }

  void await(String id) throws Exception {
    long until = System.currentTimeMillis() + 60000;
    while (System.currentTimeMillis() < until) {
      var row = db.queryForMap("SELECT status,error_message FROM vs_task WHERE id=?", id);
      if ("SUCCEEDED".equals(row.get("status"))) return;
      if ("FAILED".equals(row.get("status"))) fail("Pipeline failure: " + row.get("error_message"));
      Thread.sleep(200);
    }
    fail("Pipeline task timed out: " + id);
  }

  @TestConfiguration
  static class Models {
    @Bean
    @Primary
    ProcessingModels fixtureModels() {
      return new ProcessingModels(
          "target/full-pipeline", "unused", "fixture", "fixture", System.getenv("FFMPEG_PATH")) {
        List<Double> vector() {
          var v = new ArrayList<>(Collections.nCopies(1024, 0.0));
          v.set(0, 1.0);
          return v;
        }

        @Override
        public JsonNode describe(Path video, String hash, long start, long end) throws Exception {
          return JsonFiles.JSON.readTree("{\"summary\":\"正在喝饮料\",\"ocrTexts\":[],\"actions\":[]}");
        }

        @Override
        public List<Double> embed(String text) {
          return vector();
        }

        @Override
        public JsonNode verify(Path video, String hash, long duration, String target)
            throws Exception {
          return JsonFiles.JSON.valueToTree(
              Map.of(
                  "matches",
                  List.of(
                      Map.of(
                          "startMs",
                          1000,
                          "endMs",
                          Math.min(duration, 2000),
                          "evidence",
                          "explicit fixture response"))));
        }
      };
    }

    @Bean
    @Primary
    AgentPlanner fixturePlanner() {
      return new AgentPlanner("unused", "", "fixture", "target/full-pipeline") {
        @Override
        public Proposal propose(String target, Object evidence, long remaining) {
          return new Proposal("finish", "", "", "fixture complete");
        }
      };
    }
  }
}
