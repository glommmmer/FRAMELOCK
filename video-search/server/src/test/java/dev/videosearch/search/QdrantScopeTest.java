package dev.videosearch.search;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.videosearch.lab.JsonFiles;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** HTTP contract test only; not a live Qdrant integration claim. */
class QdrantScopeTest {
  @Test
  void allAuthorizationAndPublicationDimensionsAreSentBeforeTopK() throws Exception {
    var request = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          request.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          byte[] data = "{\"result\":[{\"payload\":{\"segmentId\":\"s\"}}]}".getBytes();
          exchange.sendResponseHeaders(200, data.length);
          try (var body = exchange.getResponseBody()) {
            body.write(data);
          }
        });
    server.start();
    try {
      var index = new QdrantIndex("http://127.0.0.1:" + server.getAddress().getPort());
      assertEquals(
          List.of("s"),
          index.search(
              "one",
              "library",
              List.of("published"),
              List.of("current-execution"),
              List.of("selected-video"),
              List.of(0.1)));
      var must = JsonFiles.JSON.readTree(request.get()).path("filter").path("must");
      Set<String> keys = new HashSet<>();
      must.forEach(x -> keys.add(x.path("key").asText()));
      assertEquals(Set.of("owner", "libraryId", "versionId", "executionId", "videoId"), keys);
      var selected =
          java.util.stream.StreamSupport.stream(must.spliterator(), false)
              .filter(x -> x.path("key").asText().equals("videoId"))
              .findFirst()
              .orElseThrow();
      assertEquals("selected-video", selected.path("match").path("any").path(0).asText());
    } finally {
      server.stop(0);
    }
  }
}
