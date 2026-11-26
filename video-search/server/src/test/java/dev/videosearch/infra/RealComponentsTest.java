package dev.videosearch.infra;

import static org.junit.jupiter.api.Assertions.*;

import dev.videosearch.search.QdrantIndex;
import dev.videosearch.storage.ObjectStorage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;

/** Opt-in real local components. Test keys/objects are unique and removed afterwards. */
@EnabledIfSystemProperty(named = "framelock.real", matches = "true")
class RealComponentsTest {
  static Map<String, String> env = new HashMap<>();

  @BeforeAll
  static void settings() throws Exception {
    env = ComponentTestSettings.load();
  }

  @Test
  void minioStoresRangeReadsAndDeletesWithoutChangingLegacyRouting() throws Exception {
    var store = new ObjectStorage("target/real-components", "minio", "", "", "", "");
    ReflectionTestUtils.setField(store, "minioEndpoint", "http://127.0.0.1:9000");
    ReflectionTestUtils.setField(store, "minioBucket", "framelock-tests");
    ReflectionTestUtils.setField(store, "minioAccess", env.get("VS_MINIO_ACCESS_KEY"));
    ReflectionTestUtils.setField(store, "minioSecret", env.get("VS_MINIO_SECRET_KEY"));
    String key = ObjectStorage.tagged("minio", "video-search/test/" + UUID.randomUUID());
    Path temp = Files.createTempFile("minio-check-", ".bin");
    try {
      Files.write(temp, new byte[] {10, 20, 30, 40});
      store.put(key, temp);
      try (var input = store.open(key)) {
        assertArrayEquals(new byte[] {10, 20, 30, 40}, input.readAllBytes());
      }
      try (var input = store.openRange(key, 1, 2)) {
        assertArrayEquals(new byte[] {20, 30}, input.readAllBytes());
      }
      assertEquals("legacy", ObjectStorage.tagged("oss", "legacy"));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.deleteUploadParts(UUID.randomUUID().toString(), List.of(key)));
    } finally {
      store.delete(key);
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void redisTokenBucketLockMemoryAndPubSub() throws Exception {
    var config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://127.0.0.1:6380")
        .setPassword(env.get("VS_REDIS_PASSWORD"))
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(2);
    var client = Redisson.create(config);
    String prefix = "framelock:test:" + UUID.randomUUID();
    try {
      var limiter = client.getRateLimiter(prefix + ":rate");
      limiter.trySetRate(RateType.OVERALL, 1, 60, RateIntervalUnit.SECONDS);
      assertTrue(limiter.tryAcquire());
      assertFalse(limiter.tryAcquire());
      var lock = client.getLock(prefix + ":lock");
      assertTrue(lock.tryLock());
      lock.unlock();
      var topic =
          client.getTopic(prefix + ":topic", org.redisson.client.codec.StringCodec.INSTANCE);
      CountDownLatch received = new CountDownLatch(1);
      int listener = topic.addListener(String.class, (ch, text) -> received.countDown());
      topic.publish("changed");
      assertTrue(received.await(5, TimeUnit.SECONDS));
      topic.removeListener(listener);
      client
          .getBucket(prefix + ":memory")
          .set(List.of("question"), java.time.Duration.ofSeconds(10));
      assertEquals(List.of("question"), client.getBucket(prefix + ":memory").get());
    } finally {
      client.getKeys().deleteByPattern(prefix + "*");
      client.shutdown();
    }
  }

  @Test
  void localOcrReadsTextThroughWsl() throws Exception {
    Path image = Path.of("target/real-components-text.png").toAbsolutePath();
    var canvas =
        new java.awt.image.BufferedImage(800, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics = canvas.createGraphics();
    graphics.setColor(java.awt.Color.WHITE);
    graphics.fillRect(0, 0, 800, 160);
    graphics.setColor(java.awt.Color.BLACK);
    graphics.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 60));
    graphics.drawString("FRAMELOCK 123", 40, 100);
    graphics.dispose();
    javax.imageio.ImageIO.write(canvas, "png", image.toFile());
    var executor = new dev.videosearch.model.MediaExecutors().ocr();
    executor.initialize();
    try {
      var ocr =
          new dev.videosearch.model.OcrBranch(
              executor, "tesseract", true, "target/real-components");
      ReflectionTestUtils.setField(ocr, "mode", "wsl");
      var result = ocr.extract(List.of(new dev.videosearch.media.FrameSampler.Frame(0, image)));
      assertEquals("READY", result.get("status"));
      assertTrue(result.get("texts").toString().contains("FRAMELOCK"));
    } finally {
      executor.shutdown();
      Files.deleteIfExists(image);
    }
  }

  @Test
  void qdrantFiltersOwnersVersionsAndDeletes() throws Exception {
    var index = new QdrantIndex("http://127.0.0.1:6333");
    String video = UUID.randomUUID().toString(), segment = UUID.randomUUID().toString();
    List<Double> vector = new ArrayList<>(Collections.nCopies(1024, 0.0));
    vector.set(0, 1.0);
    index.ensureCollection();
    try {
      index.upsert(
          List.of(
              new QdrantIndex.Point(
                  segment, video, "ver", "test-owner", "test-lib", 0, 1000, vector)),
          "execution");
      assertEquals(
          List.of(segment),
          index.search(
              "test-owner",
              "test-lib",
              List.of("ver"),
              List.of("execution"),
              List.of(video),
              vector));
      assertTrue(
          index
              .search(
                  "other", "test-lib", List.of("ver"), List.of("execution"), List.of(video), vector)
              .isEmpty());
      assertTrue(
          index
              .search(
                  "test-owner",
                  "test-lib",
                  List.of("old"),
                  List.of("execution"),
                  List.of(video),
                  vector)
              .isEmpty());
    } finally {
      index.deleteVideo("test-owner", video);
    }
    assertTrue(
        index
            .search(
                "test-owner",
                "test-lib",
                List.of("ver"),
                List.of("execution"),
                List.of(video),
                vector)
            .isEmpty());
  }
}
