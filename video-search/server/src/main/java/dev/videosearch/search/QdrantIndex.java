package dev.videosearch.search;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.lab.JsonFiles;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QdrantIndex {
  private final String base;
  private static final String COLLECTION = "framelock_text_embedding_v4_1024_visual_v2";

  public QdrantIndex(@Value("${VS_QDRANT_URL:http://localhost:6333}") String base) {
    this.base = base.replaceAll("/+$", "");
  }

  private JsonNode call(String method, String path, Object body) throws Exception {
    var response =
        dev.videosearch.model.JsonHttp.call(
            method,
            base + path,
            Map.of("Content-Type", "application/json"),
            body == null ? null : JsonFiles.JSON.writeValueAsBytes(body));
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("QDRANT_HTTP_" + response.statusCode());
    return JsonFiles.JSON.readTree(response.body());
  }

  public synchronized void ensureCollection() throws Exception {
    var response =
        dev.videosearch.model.JsonHttp.call(
            "GET", base + "/collections/" + COLLECTION, Map.of(), null);
    if (response.statusCode() == 404)
      call(
          "PUT",
          "/collections/" + COLLECTION,
          Map.of("vectors", Map.of("size", 1024, "distance", "Cosine")));
    else if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("Qdrant collection unavailable");
    else if (JsonFiles.JSON
            .readTree(response.body())
            .path("result")
            .path("config")
            .path("params")
            .path("vectors")
            .path("size")
            .asInt()
        != 1024) throw new IllegalStateException("Qdrant dimension mismatch");
    for (String field : List.of("owner", "libraryId", "videoId", "versionId", "executionId"))
      call(
          "PUT",
          "/collections/" + COLLECTION + "/index?wait=true",
          Map.of("field_name", field, "field_schema", "keyword"));
  }

  public record Point(
      String segmentId,
      String videoId,
      String versionId,
      String owner,
      String libraryId,
      long startMs,
      long endMs,
      List<Double> vector) {}

  public void upsert(List<Point> points, String execution) throws Exception {
    List<Object> data = new ArrayList<>();
    for (var point : points) {
      String id =
          UUID.nameUUIDFromBytes(
                  (point.segmentId() + ":" + execution).getBytes(StandardCharsets.UTF_8))
              .toString();
      data.add(
          Map.of(
              "id",
              id,
              "vector",
              point.vector(),
              "payload",
              Map.of(
                  "segmentId",
                  point.segmentId(),
                  "videoId",
                  point.videoId(),
                  "versionId",
                  point.versionId(),
                  "executionId",
                  execution,
                  "owner",
                  point.owner(),
                  "libraryId",
                  point.libraryId(),
                  "startMs",
                  point.startMs(),
                  "endMs",
                  point.endMs())));
    }
    call("PUT", "/collections/" + COLLECTION + "/points?wait=true", Map.of("points", data));
  }

  public List<String> search(
      String owner,
      String library,
      List<String> versions,
      List<String> executions,
      List<String> videos,
      List<Double> vector)
      throws Exception {
    if (versions.isEmpty() || executions.isEmpty() || videos.isEmpty()) return List.of();
    Object filter =
        Map.of(
            "must",
            List.of(
                Map.of("key", "owner", "match", Map.of("value", owner)),
                Map.of("key", "libraryId", "match", Map.of("value", library)),
                Map.of("key", "versionId", "match", Map.of("any", versions)),
                Map.of("key", "executionId", "match", Map.of("any", executions)),
                Map.of("key", "videoId", "match", Map.of("any", videos))));
    var response =
        call(
            "POST",
            "/collections/" + COLLECTION + "/points/search",
            Map.of("vector", vector, "limit", 20, "filter", filter, "with_payload", true));
    List<String> ids = new ArrayList<>();
    for (var hit : response.path("result")) ids.add(hit.path("payload").path("segmentId").asText());
    return ids;
  }

  public void deleteVideo(String owner, String video) throws Exception {
    call(
        "POST",
        "/collections/" + COLLECTION + "/points/delete?wait=true",
        Map.of(
            "filter",
            Map.of(
                "must",
                List.of(
                    Map.of("key", "owner", "match", Map.of("value", owner)),
                    Map.of("key", "videoId", "match", Map.of("value", video))))));
  }
}
