package dev.videosearch.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.billing.FileBudget;
import dev.videosearch.lab.JsonFiles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapts the existing asynchronous Bailian transcription API. */
@Component
public class AsrGateway {
  private final String base, key;
  private final Path root;

  @org.springframework.beans.factory.annotation.Autowired
  @org.springframework.beans.factory.annotation.Qualifier("asrExecutor")
  private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;

  public AsrGateway(
      @Value("${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com}") String base,
      @Value("${DASHSCOPE_API_KEY:}") String key,
      @Value("${app.data-dir:.data}") String data) {
    this.base = base;
    this.key = key;
    root = Path.of(data).toAbsolutePath();
  }

  private JsonNode request(String method, String endpoint, Object body, boolean async)
      throws Exception {
    if (key.isBlank()) throw new IllegalArgumentException("ASR API key is not configured");
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer " + key);
    headers.put("Content-Type", "application/json");
    if (async) headers.put("X-DashScope-Async", "enable");
    byte[] bytes = body == null ? null : JsonFiles.JSON.writeValueAsBytes(body);
    var work = executor.submit(() -> JsonHttp.call(method, base + endpoint, headers, bytes));
    JsonHttp.Reply response;
    try {
      response = work.get(50, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      work.cancel(true);
      throw e;
    } catch (Exception e) {
      work.cancel(true);
      throw new IllegalStateException("ASR request failed", e);
    }
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("ASR_HTTP_" + response.statusCode());
    return JsonFiles.JSON.readTree(response.body());
  }

  public String submit(String audioUrl, String contentHash, long durationMs) throws Exception {
    Path cache =
        root.resolve("asr-submissions/" + JsonFiles.hash(contentHash + ":fun-asr-v1") + ".json");
    if (Files.exists(cache)) return JsonFiles.read(cache).path("taskId").asText();
    var budget = new FileBudget(root.resolve("model-ledger.json"), new BigDecimal("27"));
    // Beijing fun-asr list price checked 2026-11-22: CNY 0.00022 / audio second.
    BigDecimal estimated =
        new BigDecimal("0.00022")
            .multiply(BigDecimal.valueOf(Math.max(1, (durationMs + 999) / 1000)));
    String reservation = budget.reserve("ASR_MINUTE_ESTIMATE", estimated);
    try {
      var result =
          request(
              "POST",
              "/api/v1/services/audio/asr/transcription",
              Map.of(
                  "model",
                  "fun-asr",
                  "input",
                  Map.of("file_urls", List.of(audioUrl)),
                  "parameters",
                  Map.of("channel_id", List.of(0))),
              true);
      String id = result.path("output").path("task_id").asText();
      if (!id.matches("[A-Za-z0-9_-]+"))
        throw new IllegalStateException("ASR did not return a valid task ID");
      JsonFiles.write(cache, Map.of("taskId", id, "estimatedYuan", estimated));
      budget.settle(reservation, estimated, 0, 0, id);
      return id;
    } catch (Exception error) {
      budget.unknown(reservation);
      throw error;
    }
  }

  public JsonNode status(String id) throws Exception {
    if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid ASR task ID");
    return request("GET", "/api/v1/tasks/" + id, null, false);
  }

  public JsonNode result(String url) throws Exception {
    URI uri = URI.create(url);
    String host = uri.getHost();
    if (!"https".equals(uri.getScheme())
        || host == null
        || !(host.endsWith(".aliyuncs.com") || host.endsWith(".aliyuncs.com.cn")))
      throw new IllegalArgumentException("Unexpected transcription result host");
    var response = JsonHttp.call("GET", url, Map.of(), null);
    if (response.statusCode() != 200)
      throw new IllegalStateException("ASR_RESULT_HTTP_" + response.statusCode());
    return JsonFiles.JSON.readTree(response.body());
  }
}
