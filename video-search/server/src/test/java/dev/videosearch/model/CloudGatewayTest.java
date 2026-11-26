package dev.videosearch.model;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.videosearch.billing.FileBudget;
import dev.videosearch.lab.JsonFiles;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CloudGatewayTest {
  @TempDir Path temporary;
  HttpServer server;

  @AfterEach
  void stop() {
    CloudGateway.clearAudit();
    if (server != null) server.stop(0);
  }

  CloudGateway gateway(String response, int status, AtomicInteger calls) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          }
        });
    server.start();
    return new CloudGateway(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-key",
        "test-model",
        temporary.resolve("cache"),
        budget());
  }

  FileBudget budget() {
    return new FileBudget(temporary.resolve("ledger.json"), new BigDecimal("27"));
  }

  @Test
  void cachedEmbeddingIsNotBilledTwice() throws Exception {
    var vector = Collections.nCopies(1024, 0.1);
    String body =
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "id",
                "test",
                "usage",
                Map.of("prompt_tokens", 100),
                "data",
                List.of(Map.of("embedding", vector))));
    var calls = new AtomicInteger();
    var gateway = gateway(body, 200, calls);
    List<Map<String, Object>> receipts = new ArrayList<>();
    CloudGateway.auditTo(receipts::add);
    assertEquals(1024, gateway.embed("同一查询").size());
    gateway.embed("同一查询");
    assertEquals(1, calls.get());
    assertEquals(2, receipts.size());
    assertEquals(true, receipts.get(1).get("cacheHit"));
    assertEquals(BigDecimal.ZERO, receipts.get(1).get("estimatedOrReservedYuan"));
    assertEquals(1, budget().summary().path("calls").asInt());
    assertEquals(0, budget().summary().path("unsettledCalls").asInt());
  }

  @Test
  void providerFailureRetainsReservationAndDoesNotLogResponseBody() throws Exception {
    var gateway =
        gateway(
            "{\"error\":{\"code\":\"RateLimit\",\"message\":\"sensitive-vendor-text\"}}",
            429,
            new AtomicInteger());
    var error = assertThrows(IllegalStateException.class, () -> gateway.embed("x"));
    assertEquals("MODEL_HTTP_429:RateLimit", error.getMessage());
    assertFalse(error.getMessage().contains("sensitive"));
    assertEquals(1, budget().summary().path("unsettledCalls").asInt());
    assertEquals(
        0,
        new BigDecimal("0.01")
            .compareTo(budget().summary().path("accountedOrReservedYuan").decimalValue()));
  }

  @Test
  void malformedProviderJsonIsNotConvertedIntoFakeResults() throws Exception {
    var gateway = gateway("{broken", 200, new AtomicInteger());
    assertThrows(Exception.class, () -> gateway.embed("x"));
    assertEquals(1, budget().summary().path("unsettledCalls").asInt());
  }

  @Test
  void maximumVisualContextIsCoveredByItsReservation() throws Exception {
    String body =
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "id",
                "large-context",
                "usage",
                Map.of("prompt_tokens", 260096, "completion_tokens", 2048),
                "choices",
                List.of(Map.of("message", Map.of("content", "{}")))));
    var gateway = gateway(body, 200, new AtomicInteger());
    Path image = temporary.resolve("frame.jpg");
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "jpg",
        image.toFile());
    gateway.visual(
        "usage-contract-test", List.of(new dev.videosearch.media.FrameSampler.Frame(0, image)));
    var record = JsonFiles.read(temporary.resolve("ledger.json")).path(0);
    assertEquals(
        0,
        new BigDecimal("0.841728").compareTo(record.path("chargedOrReservedYuan").decimalValue()));
    assertTrue(
        record
                .path("maximumReservedYuan")
                .decimalValue()
                .compareTo(record.path("chargedOrReservedYuan").decimalValue())
            >= 0);
  }
}
