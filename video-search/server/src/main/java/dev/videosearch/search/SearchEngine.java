package dev.videosearch.search;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.agent.AgentPlanner;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.MediaCache;
import dev.videosearch.model.ProcessingModels;
import dev.videosearch.task.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SearchEngine implements SearchProcessor {
  private final java.util.concurrent.ScheduledExecutorService deadlines =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("search-deadline-", 0).factory());

  @jakarta.annotation.PreDestroy
  void close() {
    deadlines.shutdownNow();
  }

  private final JdbcTemplate db;
  private final PersistentTasks tasks;
  private final ProcessingModels models;
  private final MediaCache media;
  private final QdrantIndex index;
  private final AgentPlanner planner;
  private final dev.videosearch.agent.AgentCritic critic;
  private final boolean localIndex;
  private final dev.videosearch.model.VideoContexts videoContexts;
  private final dev.videosearch.agent.AgentCheckpoints checkpoints;
  private final FollowUpContext followups;

  public SearchEngine(
      JdbcTemplate db,
      PersistentTasks tasks,
      ProcessingModels models,
      MediaCache media,
      QdrantIndex index,
      AgentPlanner planner,
      dev.videosearch.agent.AgentCheckpoints checkpoints,
      FollowUpContext followups,
      dev.videosearch.model.VideoContexts videoContexts,
      @Value("${app.index-mode:qdrant}") String mode) {
    this.db = db;
    this.tasks = tasks;
    this.models = models;
    this.media = media;
    this.index = index;
    this.planner = planner;
    this.critic = new dev.videosearch.agent.AgentCritic(models);
    this.checkpoints = checkpoints;
    this.followups = followups;
    this.videoContexts = videoContexts;
    localIndex = mode.equals("local");
  }

  @Override
  public void process(PersistentTasks.Lease lease) throws Exception {
    var input = JsonFiles.JSON.readTree((String) lease.row().get("input_json"));
    Run run = new Run(lease, input);
    Thread executing = Thread.currentThread();
    var timer =
        deadlines.schedule(
            executing::interrupt,
            Math.max(1, run.deadline - System.currentTimeMillis()),
            java.util.concurrent.TimeUnit.MILLISECONDS);
    try {
      try {
        run.execute();
      } catch (DeadlineReached e) {
        run.exit = "DEADLINE";
      } catch (ToolLimitReached e) {
        run.exit = "TOOL_LIMIT";
      } catch (InterruptedException e) {
        if (System.currentTimeMillis() < run.deadline) throw e;
        run.exit = "DEADLINE";
      } catch (Exception e) {
        if (System.currentTimeMillis() < run.deadline || !tasks.active(lease)) throw e;
        run.exit = "DEADLINE";
      }
    } finally {
      timer.cancel(false);
      Thread.interrupted();
      dev.videosearch.model.CloudGateway.clearAudit();
    }
    run.finish();
  }

  private static class DeadlineReached extends RuntimeException {}

  private static class ToolLimitReached extends RuntimeException {}

  private final class Run {
    final PersistentTasks.Lease lease;
    final String owner, library;
    String query, parentSearchId, originalQuestion;
    boolean retrievalComplete;
    int retrievalStep;
    String phase = "CONTEXT";
    final List<String> degraded = new ArrayList<>();
    AgentPlanner.Proposal pending;
    final boolean agent;
    final long deadline;
    final List<Map<String, Object>> videos;
    final Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
    final List<Map<String, Object>> trace = new ArrayList<>(), matches = new ArrayList<>();
    final Set<String> inspected = new HashSet<>();
    List<Map<String, Object>> modelUsage = List.of();
    int calls, rounds;
    int toolCeiling = 8;
    String exit = "COMPLETE";

    Run(PersistentTasks.Lease lease, JsonNode input) throws Exception {
      this.lease = lease;
      owner = (String) lease.row().get("owner");
      library = input.path("libraryId").asText();
      query = input.path("query").asText();
      originalQuestion = query;
      parentSearchId = input.path("parentSearchId").asText();
      agent = input.path("mode").asText().equals("AGENT");
      var selected = new HashSet<String>();
      for (var id : input.path("videoIds")) selected.add(id.asText());
      videos =
          db
              .queryForList(
                  "SELECT * FROM vs_video WHERE owner=? AND library_id=? AND deleted=FALSE AND"
                      + " active_version IS NOT NULL",
                  owner,
                  library)
              .stream()
              .filter(v -> selected.isEmpty() || selected.contains(v.get("id")))
              .toList();
      long started = System.currentTimeMillis();
      Object saved = lease.row().get("output_json");
      if (saved != null) {
        var state = checkpoints.load(lease);
        started = state.path("startedAt").asLong(started);
        calls = state.path("toolCalls").asInt();
        rounds = state.path("rounds").asInt();
        for (var event : state.path("trace"))
          trace.add(JsonFiles.JSON.convertValue(event, Map.class));
        for (var event : state.path("matches"))
          matches.add(JsonFiles.JSON.convertValue(event, Map.class));
        for (var id : state.path("inspected")) inspected.add(id.asText());
        retrievalComplete = state.path("retrievalComplete").asBoolean();
        retrievalStep = state.path("retrievalStep").asInt();
        if (state.has("effectiveQuery")) query = state.path("effectiveQuery").asText();
        for (var candidate : state.path("candidates")) {
          var c = JsonFiles.JSON.convertValue(candidate, Map.class);
          candidates.put(c.get("id").toString(), c);
        }
        for (var warning : state.path("degraded")) degraded.add(warning.asText());
        if (state.hasNonNull("pendingPlan"))
          pending =
              JsonFiles.JSON.treeToValue(state.path("pendingPlan"), AgentPlanner.Proposal.class);
      }
      deadline = started + 120000;
    }

    void check() {
      if (!tasks.active(lease)) throw new IllegalArgumentException("Search cancelled or replaced");
      if (System.currentTimeMillis() >= deadline) throw new DeadlineReached();
    }

    void beginTool() {
      check();
      if (calls >= 8) throw new ToolLimitReached();
      calls++;
      auditTool();
    }

    void auditTool() {
      modelUsage = new ArrayList<>();
      dev.videosearch.model.CloudGateway.auditTo(modelUsage::add);
    }

    void execute() throws Exception {
      if (videos.isEmpty()) {
        exit = "NO_PUBLISHED_VIDEOS";
        return;
      }
      if (!retrievalComplete) {
        if (retrievalStep == 0 && !parentSearchId.isBlank())
          query = followups.resolve(owner, parentSearchId, originalQuestion);
        if (retrievalStep == 0) {
          retrievalStep = 1;
          retrieve(query, true);
        }
        if (retrievalStep == 1) {
          retrievalStep = 2;
          retrieve(query, false);
        }
        retrievalComplete = true;
        phase = "EXECUTOR";
        checkpoint();
      }
      // RRF rank is deterministic and persisted in every checkpoint; windows may share a video.
      var ranked = ranked();
      toolCeiling = agent ? 6 : 8;
      for (var candidate : ranked.stream().limit(agent ? 2 : 5).toList()) {
        if (inspected.contains(candidate.get("id").toString())) continue;
        if (calls >= toolCeiling) break;
        inspect(candidate, false);
        if (matches.size() >= 5) break;
      }
      toolCeiling = 8;
      while (agent
          && calls < 8
          && (rounds < 2 || pending != null)
          && EventIntervals.merge(matches).size() < 5) {
        check();
        var evidence =
            Map.of(
                "candidates",
                ranked().stream()
                    .limit(20)
                    .map(
                        c ->
                            Map.of(
                                "id",
                                c.get("id"),
                                "description",
                                c.get("description"),
                                "startMs",
                                c.get("start_ms"),
                                "endMs",
                                c.get("end_ms")))
                    .toList(),
                "trace",
                trace,
                "matched",
                matches.size(),
                "remainingTools",
                8 - calls);
        auditTool();
        var proposed =
            pending == null
                ? planner.propose(query, evidence, deadline - System.currentTimeMillis())
                : pending;
        if (pending == null) rounds++;
        pending = proposed;
        phase = "PLANNER";
        trace.add(
            Map.of(
                "role",
                "Planner",
                "tool",
                proposed.tool(),
                "reason",
                proposed.reason() == null ? "" : proposed.reason(),
                "round",
                rounds,
                "modelUsage",
                modelUsage));
        checkpoint();
        if (proposed.tool().equals("finish")) {
          exit = "PLANNER_FINISHED";
          pending = null;
          break;
        }
        int required =
            List.of("searchVisualDescriptions", "searchTranscript").contains(proposed.tool())
                ? 2
                : 1;
        if (8 - calls < required) {
          exit = "TOOL_LIMIT";
          pending = null;
          checkpoint();
          break;
        }
        switch (proposed.tool()) {
          case "searchVisualDescriptions", "searchTranscript" -> {
            String rewrite = proposed.query();
            if (rewrite == null || rewrite.isBlank() || rewrite.length() > 500)
              throw new IllegalArgumentException("Invalid retrieval rewrite");
            retrieve(rewrite, proposed.tool().equals("searchVisualDescriptions"));
            var next =
                ranked().stream()
                    .filter(c -> !inspected.contains(c.get("id").toString()))
                    .findFirst();
            if (next.isPresent() && calls < 8) inspect(next.get(), false);
          }
          case "inspectClip", "expandContext" -> {
            var candidate = candidates.get(proposed.candidateId());
            if (candidate == null)
              throw new IllegalArgumentException("Planner candidate outside authorized scope");
            inspect(candidate, proposed.tool().equals("expandContext"));
          }
          default -> throw new IllegalArgumentException("Unsupported Planner tool");
        }
        pending = null;
        phase = "CRITIC";
        checkpoint();
      }
      if (calls >= 8 && EventIntervals.merge(matches).size() < 5) exit = "TOOL_LIMIT";
    }

    List<Map<String, Object>> ranked() {
      return candidates.values().stream()
          .sorted(
              Comparator.comparingDouble(
                      (Map<String, Object> c) -> ((Number) c.get("score")).doubleValue())
                  .reversed()
                  .thenComparing(c -> c.get("id").toString()))
          .toList();
    }

    void retrieve(String term, boolean visual) throws Exception {
      beginTool();
      long began = System.currentTimeMillis();
      List<Map<String, Object>> rows = new ArrayList<>();
      for (var video : videos) {
        var context =
            videoContexts.load(
                owner, video.get("id").toString(), video.get("active_version").toString());
        for (var segment : context.segments()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", segment.segmentId());
          row.put("video_id", context.videoId());
          row.put("version_id", context.versionId());
          row.put("start_ms", segment.startMs());
          row.put("end_ms", segment.endMs());
          row.put("description", segment.visualDescription());
          row.put("ocr_text", segment.ocrText());
          row.put("embedding", JsonFiles.JSON.writeValueAsString(segment.embedding()));
          row.put(
              "text",
              visual
                  ? segment.visualDescription() + " " + segment.ocrText()
                  : segment.speech().stream()
                      .map(dev.videosearch.model.VideoContext.Transcript::text)
                      .reduce("", (a, b) -> a + " " + b));
          rows.add(row);
        }
      }
      var lexical =
          rows.stream()
              .filter(r -> lexical(r.get("text").toString(), term) > 0)
              .sorted(
                  Comparator.comparingDouble(
                          (Map<String, Object> r) -> lexical(r.get("text").toString(), term))
                      .reversed())
              .limit(20)
              .toList();
      for (int i = 0; i < lexical.size(); i++) add(lexical.get(i), 1.0 / (61 + i));
      if (visual) {
        List<Double> vector = null;
        try {
          vector = models.embed(term);
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception error) {
          if (error instanceof IllegalArgumentException) throw error;
          if (error.getMessage() != null && error.getMessage().contains("BUDGET")) throw error;
          degraded.add("Embedding 暂不可用，本次使用关键词查找");
        }
        if (vector != null) {
          List<String> ids;
          if (localIndex) ids = localVector(rows, vector);
          else
            try {
              for (var video : videos)
                if (db.queryForObject(
                        "SELECT COUNT(*) FROM vs_index_sync WHERE video_id=? AND version_id=? AND"
                            + " execution_id=?",
                        Integer.class,
                        video.get("id"),
                        video.get("active_version"),
                        video.get("index_execution_id"))
                    == 0) throw new IllegalStateException("Index rebuild in progress");
              ids =
                  index.search(
                      owner,
                      library,
                      videos.stream().map(v -> v.get("active_version").toString()).toList(),
                      videos.stream().map(v -> v.get("index_execution_id").toString()).toList(),
                      videos.stream().map(v -> v.get("id").toString()).toList(),
                      vector);
            } catch (InterruptedException e) {
              throw e;
            } catch (Exception error) {
              degraded.add("Qdrant 暂不可用，本次使用已有向量和关键词查找");
              ids = localVector(rows, vector);
            }
          for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            var row = rows.stream().filter(r -> r.get("id").equals(id)).findFirst();
            if (row.isPresent()) add(row.get(), 1.0 / (61 + i));
          }
        }
      }
      trace.add(
          Map.of(
              "role",
              "Executor",
              "tool",
              visual ? "searchVisualDescriptions" : "searchTranscript",
              "query",
              term,
              "candidateCount",
              candidates.size(),
              "elapsedMs",
              System.currentTimeMillis() - began,
              "modelUsage",
              modelUsage));
      checkpoint();
    }

    List<String> localVector(List<Map<String, Object>> rows, List<Double> vector) throws Exception {
      Map<String, Double> scores = new HashMap<>();
      for (var row : rows) {
        List<Double> stored = new ArrayList<>();
        for (var value : JsonFiles.JSON.readTree(row.get("embedding").toString()))
          stored.add(value.asDouble());
        if (stored.size() == vector.size())
          scores.put(row.get("id").toString(), Retrieval.cosine(stored, vector));
      }
      return scores.entrySet().stream()
          .sorted(
              Map.Entry.<String, Double>comparingByValue()
                  .reversed()
                  .thenComparing(Map.Entry.comparingByKey()))
          .limit(20)
          .map(Map.Entry::getKey)
          .toList();
    }

    void add(Map<String, Object> row, double score) {
      String id = row.get("id").toString();
      var candidate = candidates.computeIfAbsent(id, x -> new LinkedHashMap<>(row));
      candidate.remove("embedding");
      candidate.put("score", ((Number) candidate.getOrDefault("score", 0.0)).doubleValue() + score);
    }

    void inspect(Map<String, Object> candidate, boolean expand) throws Exception {
      beginTool();
      String id = candidate.get("id").toString(), videoId = candidate.get("video_id").toString();
      var video =
          videos.stream().filter(v -> v.get("id").equals(videoId)).findFirst().orElseThrow();
      if (db.queryForObject(
              "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE AND"
                  + " active_version=?",
              Long.class,
              videoId,
              owner,
              candidate.get("version_id"))
          == 0) throw new IllegalArgumentException("Video deleted or version changed");
      long duration = ((Number) video.get("duration_ms")).longValue(),
          cs = ((Number) candidate.get("start_ms")).longValue(),
          ce = ((Number) candidate.get("end_ms")).longValue();
      long start = Math.max(0, cs - (expand ? 25000 : 5000)),
          end = Math.min(duration, ce + (expand ? 25000 : 5000));
      if (end - start > 60000) end = start + 60000;
      if (query.contains("完整样本")) {
        if (duration > 60000) {
          inspected.add(id);
          trace.add(
              Map.of(
                  "role",
                  "Critic",
                  "tool",
                  "inspectClip",
                  "candidateId",
                  id,
                  "videoId",
                  videoId,
                  "reason",
                  "完整样本超过60秒核验上限，不能确认全程条件",
                  "boundaryConfirmed",
                  false));
          checkpoint();
          return;
        }
        start = 0;
        end = duration;
      }
      long began = System.currentTimeMillis();
      var original =
          media.video(video.get("object_key").toString(), video.get("file_hash").toString());
      var clip = media.clip(original, video.get("file_hash").toString(), start, end);
      String hash = JsonFiles.hash(video.get("file_hash") + ":" + start + ":" + end + ":clip-v1");
      check();
      phase = "CRITIC";
      var evidence = critic.review(clip, hash, end - start, query);
      check();
      boolean touchesEdge = false;
      for (var event : evidence.path("matches"))
        if ((event.path("startMs").asLong() <= 500 && start > 0)
            || (event.path("endMs").asLong() >= end - start - 500 && end < duration))
          touchesEdge = true;
      if (!expand && touchesEdge && calls < toolCeiling) {
        trace.add(
            Map.of(
                "role",
                "Critic",
                "tool",
                "inspectClip",
                "candidateId",
                id,
                "videoId",
                videoId,
                "startMs",
                start,
                "endMs",
                end,
                "evidence",
                evidence,
                "elapsedMs",
                System.currentTimeMillis() - began,
                "modelUsage",
                modelUsage));
        trace.add(
            Map.of(
                "role",
                "Executor",
                "tool",
                "automaticContextExpansion",
                "candidateId",
                id,
                "reason",
                "动作触及候选范围边缘，继续读取邻接画面"));
        checkpoint();
        inspect(candidate, true);
        return;
      }
      for (var event : evidence.path("matches")) {
        long localStart = event.path("startMs").asLong(), localEnd = event.path("endMs").asLong();
        boolean confirmed =
            event.path("boundaryConfirmed").asBoolean(true)
                && (localStart > 500 || start == 0)
                && (localEnd < end - start - 500 || end == duration);
        var match = new LinkedHashMap<String, Object>();
        match.put("candidateId", id);
        match.put("videoId", videoId);
        match.put("versionId", candidate.get("version_id"));
        match.put("name", video.get("name"));
        match.put("startMs", start + localStart);
        match.put("endMs", start + localEnd);
        match.put("evidence", event.path("evidence").asText());
        match.put("verified", true);
        match.put("boundaryConfirmed", confirmed);
        match.put("source", "/api/v1/videos/" + videoId + "/content");
        match.put(
            "thumbnail", "/api/v1/videos/" + videoId + "/thumbnail?timeMs=" + (start + localStart));
        matches.add(match);
      }
      inspected.add(id);
      trace.add(
          Map.of(
              "role",
              "Critic",
              "tool",
              expand ? "expandContext" : "inspectClip",
              "candidateId",
              id,
              "videoId",
              videoId,
              "startMs",
              start,
              "endMs",
              end,
              "evidence",
              evidence,
              "elapsedMs",
              System.currentTimeMillis() - began,
              "modelUsage",
              modelUsage));
      checkpoint();
    }

    Map<String, Object> state() {
      var saved = new LinkedHashMap<String, Object>();
      saved.put("candidates", candidates.values());
      saved.put("retrievalComplete", retrievalComplete);
      saved.put("retrievalStep", retrievalStep);
      saved.put("pendingPlan", pending);
      saved.put("effectiveQuery", query);
      saved.put("degraded", degraded);
      saved.put("phase", phase);
      saved.put(
          "completion",
          List.of("TOOL_LIMIT", "DEADLINE").contains(exit) ? "LIMIT_REACHED" : "COMPLETED");
      saved.putAll(
          Map.of(
              "startedAt",
              deadline - 120000,
              "toolCalls",
              calls,
              "rounds",
              rounds,
              "trace",
              trace,
              "matches",
              EventIntervals.merge(matches),
              "inspected",
              inspected,
              "exitReason",
              exit,
              "indexMode",
              localIndex ? "local" : "qdrant",
              "searchedVideos",
              videos.stream()
                  .map(v -> Map.of("videoId", v.get("id"), "versionId", v.get("active_version")))
                  .toList()));
      return saved;
    }

    void checkpoint() throws Exception {
      check();
      checkpoints.save(lease, phase, state());
    }

    void finish() {
      matches.removeIf(
          m ->
              db.queryForObject(
                      "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE AND"
                          + " active_version=?",
                      Long.class,
                      m.get("videoId"),
                      owner,
                      m.get("versionId"))
                  == 0);
      var result = new LinkedHashMap<>(state());
      boolean limited = List.of("TOOL_LIMIT", "DEADLINE").contains(exit);
      String message =
          limited
              ? (matches.isEmpty()
                  ? (exit.equals("DEADLINE")
                      ? "这次查找超时了，还没有确认到符合描述的片段。"
                      : "这次检查次数已用完，还没有确认到符合描述的片段。")
                  : "已找到这些片段，还有候选未检查。")
              : (matches.isEmpty() ? "这次没找到符合描述的片段。" : "已返回通过原画面核验的片段。");
      result.put("message", message);
      if (tasks.succeed(lease, result, () -> {}))
        followups.remember(owner, lease.id(), parentSearchId, originalQuestion);
    }
  }

  static double lexical(String text, String query) {
    String a = text.toLowerCase(Locale.ROOT),
        b = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    int count = 0;
    for (int i = 0; i + 1 < b.length(); i++) if (a.contains(b.substring(i, i + 2))) count++;
    return count;
  }
}
