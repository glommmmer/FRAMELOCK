package dev.videosearch.lab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.billing.FileBudget;
import dev.videosearch.media.FrameSampler;
import dev.videosearch.model.CloudGateway;
import dev.videosearch.model.VisionAnalyzer;
import dev.videosearch.search.Retrieval;
import dev.videosearch.search.Retrieval.Segment;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** M0 runs on anonymous pixels and queries; annotations are read only by final evaluation. */
public final class Lab {
  public record Clip(String id, Path path, String sha256, long durationMs) {}

  public record Match(String videoId, long startMs, long endMs, String evidence) {}

  public record QueryResult(
      String queryId,
      String query,
      List<Match> hits,
      long elapsedMs,
      List<Map<String, Object>> trace) {}

  private record Inspection(Clip clip, JsonNode result, long elapsedMs) {}

  public static void evaluateSaved(String[] args) throws Exception {
    if (args.length != 3 && args.length != 4)
      throw new IllegalArgumentException("evaluate <projectRoot> <fixtureRoot> [predictionOutput]");
    Path output = args.length == 4 ? Path.of(args[3]) : Path.of(args[1]).resolve("output/m0"),
        fixture = Path.of(args[2]);
    var type =
        JsonFiles.JSON.getTypeFactory().constructCollectionType(List.class, QueryResult.class);
    List<QueryResult> predictions =
        JsonFiles.JSON.readValue(Files.readString(output.resolve("predictions.json")), type);
    if (predictions.stream().map(QueryResult::queryId).distinct().count() != predictions.size())
      throw new IllegalArgumentException("Duplicate query predictions");
    int expected = JsonFiles.read(fixture.resolve("queries.json")).path("queries").size();
    if (predictions.size() != expected)
      throw new IllegalArgumentException("Incomplete prediction set");
    evaluate(fixture, output, predictions, "");
  }

  public static void run(String[] args) throws Exception {
    Map<String, String> options = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("--cloud")) {
        options.put("cloud", "true");
        continue;
      }
      if (!args[i].startsWith("--") || i + 1 >= args.length)
        throw new IllegalArgumentException("Expected --option value");
      options.put(args[i].substring(2), args[++i]);
    }
    if (!options.containsKey("cloud"))
      throw new IllegalArgumentException("Explicit --cloud is required for paid calls");
    Path root = Path.of(options.getOrDefault("root", "..")).toAbsolutePath().normalize();
    Path fixture =
        Path.of(
                options.getOrDefault(
                    "fixture", root.resolve("samples/beverage/fixture-v1").normalize().toString()))
            .toAbsolutePath();
    long interval = Long.parseLong(options.getOrDefault("sample-interval-ms", "1000"));
    if (interval != 1000 && interval != 2000)
      throw new IllegalArgumentException("M0 sampling interval must be 1000 or 2000 ms");
    String modeSuffix =
        System.getenv().getOrDefault("VISION_INPUT_MODE", "images").equals("video") ? "-video" : "";
    Path output = root.resolve("output/m0" + modeSuffix + (interval == 2000 ? "-2s" : ""));
    Files.createDirectories(output);
    Path ffmpeg =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "FFMPEG_PATH",
                    root.resolve("tools/ffmpeg/win-x64/ffmpeg.exe").normalize().toString()));
    var budget = new FileBudget(root.resolve(".data/model-ledger.json"), new BigDecimal("27"));
    var cloud =
        new CloudGateway(
            System.getenv().getOrDefault("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com"),
            System.getenv("DASHSCOPE_API_KEY"),
            System.getenv().getOrDefault("VISION_MODEL", "qwen3-vl-plus"),
            root.resolve(".data/model-cache"),
            budget);
    var sampler = new FrameSampler(ffmpeg, root.resolve(".data/frame-cache"));
    var analyzer = new VisionAnalyzer(cloud, sampler);
    List<Clip> clips = new ArrayList<>();
    for (JsonNode c : JsonFiles.read(fixture.resolve("annotations.json")).path("clips")) {
      Path path = fixture.resolve(c.path("clipPath").asText()).normalize();
      if (!path.startsWith(fixture) || !Files.isRegularFile(path))
        throw new IllegalArgumentException("Invalid fixture path");
      String hash = JsonFiles.hash(Files.readAllBytes(path));
      if (!hash.equals(c.path("clipSha256").asText()))
        throw new IllegalArgumentException("Fixture hash mismatch");
      clips.add(
          new Clip(
              c.path("clipId").asText(),
              path,
              hash,
              c.path("range").path(1).asLong() - c.path("range").path(0).asLong()));
    }
    List<Segment> segments = new ArrayList<>();
    for (Clip clip : clips) {
      for (long start = 0; start < clip.durationMs(); start += 5000) {
        if (start > 0 && clip.durationMs() - start <= 5000) break;
        long end = Math.min(clip.durationMs(), start + 10000);
        String instruction =
            "不参考任何检索问题，描述本时间窗口实际发生的动作。返回"
                + " {\"summary\":\"简洁中文描述\",\"actions\":[{\"action\":\"动作描述\",\"startMs\":0,\"endMs\":1000,\"evidence\":\"可见依据\"}]}。时间必须在"
                + " "
                + start
                + " 到 "
                + end
                + " 毫秒之间。区分倒液体、展示容器与饮用。没有发生的动作不要写。";
        JsonNode description =
            cloud.visual(
                instruction, sampler.sample(clip.path(), clip.sha256(), start, end, interval));
        String summary = description.path("summary").asText();
        if (summary.isBlank()) throw new IllegalStateException("Missing visual summary");
        String searchable = summary + "\n" + description.path("actions").toString();
        segments.add(
            new Segment(
                clip.id() + ":" + start,
                clip.id(),
                start,
                end,
                searchable,
                cloud.embed(searchable)));
        JsonFiles.write(output.resolve("index.json"), segments);
        System.out.println("INDEXED " + clip.id() + " " + start + ".." + end);
      }
    }
    if (options.getOrDefault("stage", "all").equals("index")) {
      JsonFiles.write(output.resolve("budget.json"), budget.summary());
      return;
    }
    List<QueryResult> results = new ArrayList<>();
    String only = options.getOrDefault("query", "");
    for (JsonNode q : JsonFiles.read(fixture.resolve("queries.json")).path("queries")) {
      if (!only.isBlank() && !only.equals(q.path("queryId").asText())) continue;
      long began = System.nanoTime();
      String query = q.path("query").asText();
      List<Map<String, Object>> trace = new ArrayList<>();
      List<Match> hits = new ArrayList<>();
      List<String> scope = new ArrayList<>();
      q.path("scope").forEach(id -> scope.add(id.asText()));
      var ranked =
          Retrieval.rank(
              segments.stream().filter(s -> scope.contains(s.videoId())).toList(),
              query,
              cloud.embed(query),
              5);
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var pending = new ArrayList<java.util.concurrent.Future<Inspection>>();
        for (Segment candidate : ranked)
          pending.add(
              pool.submit(
                  () -> {
                    Clip clip =
                        clips.stream()
                            .filter(c -> c.id().equals(candidate.videoId()))
                            .findFirst()
                            .orElseThrow();
                    long called = System.nanoTime();
                    return new Inspection(
                        clip,
                        analyzer.verify(clip.path(), clip.sha256(), clip.durationMs(), query),
                        (System.nanoTime() - called) / 1_000_000);
                  }));
        for (var future : pending) {
          var inspection = future.get();
          Clip clip = inspection.clip();
          JsonNode verified = inspection.result();
          if (!verified.path("matches").isArray())
            throw new IllegalStateException("Invalid verifier result");
          trace.add(
              Map.of(
                  "tool",
                  "inspectClip",
                  "videoId",
                  clip.id(),
                  "range",
                  List.of(0, clip.durationMs()),
                  "result",
                  verified,
                  "elapsedMs",
                  inspection.elapsedMs()));
          for (JsonNode event : verified.path("matches")) {
            long start = event.path("startMs").asLong(-1), end = event.path("endMs").asLong(-1);
            if (start < 0 || end <= start || end > clip.durationMs())
              throw new IllegalStateException("Model time outside inspected range");
            hits.add(new Match(clip.id(), start, end, event.path("evidence").asText()));
          }
          System.out.println(
              "VERIFIED "
                  + q.path("queryId").asText()
                  + " "
                  + clip.id()
                  + " matches="
                  + verified.path("matches").size());
        }
      }
      results.add(
          new QueryResult(
              q.path("queryId").asText(),
              query,
              hits.stream().limit(5).toList(),
              (System.nanoTime() - began) / 1_000_000,
              trace));
      JsonFiles.write(
          output.resolve(only.isBlank() ? "predictions.json" : "predictions-" + only + ".json"),
          results);
      JsonFiles.write(output.resolve("budget.json"), budget.summary());
    }
    evaluate(fixture, output, results, only);
    JsonFiles.write(output.resolve("budget.json"), budget.summary());
  }

  private static void evaluate(Path fixture, Path output, List<QueryResult> results, String only)
      throws Exception {
    List<Map<String, Object>> cases = new ArrayList<>();
    List<Long> starts = new ArrayList<>(), ends = new ArrayList<>();
    double recall = 0, precision = 0;
    int positives = 0, negatives = 0, falsePositives = 0;
    for (JsonNode q : JsonFiles.read(fixture.resolve("queries.json")).path("queries")) {
      Optional<QueryResult> found =
          results.stream().filter(r -> r.queryId().equals(q.path("queryId").asText())).findFirst();
      if (found.isEmpty()) continue;
      List<Evaluator.Event> expected = new ArrayList<>();
      for (JsonNode e : q.path("expectedEvents"))
        expected.add(
            new Evaluator.Event(
                e.path("videoId").asText(), e.path("startMs").asLong(), e.path("endMs").asLong()));
      var predicted =
          found.get().hits().stream()
              .map(e -> new Evaluator.Event(e.videoId(), e.startMs(), e.endMs()))
              .toList();
      var score = Evaluator.score(expected, predicted, 5);
      if (expected.isEmpty()) {
        negatives++;
        if (score.falsePositive()) falsePositives++;
      } else {
        positives++;
        recall += score.recall();
        precision += score.precision();
      }
      starts.addAll(score.startErrors());
      ends.addAll(score.endErrors());
      cases.add(
          Map.of(
              "queryId",
              found.get().queryId(),
              "query",
              found.get().query(),
              "score",
              score,
              "expected",
              expected,
              "predicted",
              predicted,
              "elapsedMs",
              found.get().elapsedMs()));
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("createdAt", Instant.now().toString());
    report.put("dataset", "development-only");
    report.put("mode", "B-visual-description-fixed-verification");
    if (Files.exists(output.resolve("mode.json")))
      report.put("mode", JsonFiles.read(output.resolve("mode.json")).path("mode").asText());
    report.put("visualInputMode", System.getenv().getOrDefault("VISION_INPUT_MODE", "images"));
    report.put("verifierVersion", "hard-conditions-v3-scope-validation");
    report.put("queriesEvaluated", cases.size());
    report.put("recallAt5", positives == 0 ? null : recall / positives);
    report.put("precisionAt5", positives == 0 ? null : precision / positives);
    report.put("negativeQueries", negatives);
    report.put("falsePositiveQueries", falsePositives);
    report.put("startErrorMedianMs", starts.isEmpty() ? null : Evaluator.median(starts));
    report.put("endErrorMedianMs", ends.isEmpty() ? null : Evaluator.median(ends));
    report.put(
        "passedM0",
        cases.size() == 12
            && positives > 0
            && recall / positives >= 0.8
            && negatives >= 4
            && falsePositives <= 1);
    report.put("cases", cases);
    JsonFiles.write(
        output.resolve(only.isBlank() ? "evaluation.json" : "evaluation-" + only + ".json"),
        report);
    ReportRenderer.render(output, fixture, results, report);
    System.out.println(
        "EVALUATION recall@5="
            + report.get("recallAt5")
            + " false-positive-queries="
            + falsePositives
            + " passed="
            + report.get("passedM0"));
  }
}
