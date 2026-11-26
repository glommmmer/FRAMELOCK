package dev.videosearch.search;

import dev.videosearch.api.ApiError;
import dev.videosearch.lab.JsonFiles;
import java.time.Duration;
import java.util.*;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FollowUpContext {
  private final JdbcTemplate db;
  private final ObjectProvider<RedissonClient> redis;

  public FollowUpContext(JdbcTemplate db, ObjectProvider<RedissonClient> redis) {
    this.db = db;
    this.redis = redis;
  }

  public Map<String, Object> parent(String owner, String id) {
    var rows =
        db.queryForList(
            "SELECT * FROM vs_task WHERE id=? AND owner=? AND kind='SEARCH' AND status='SUCCEEDED'",
            id,
            owner);
    if (rows.isEmpty()) throw ApiError.missing();
    return rows.getFirst();
  }

  private String key(String owner, String id) {
    return "framelock:followup:" + owner + ":" + id;
  }

  public String resolve(String owner, String parentId, String question) throws Exception {
    var parent = parent(owner, parentId);
    var input = JsonFiles.JSON.readTree(parent.get("input_json").toString());
    var output = JsonFiles.JSON.readTree(parent.get("output_json").toString());
    List<Object> visible = new ArrayList<>();
    for (var match : output.path("matches")) {
      if (db.queryForObject(
              "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE AND"
                  + " active_version=?",
              Long.class,
              match.path("videoId").asText(),
              owner,
              match.path("versionId").asText())
          == 0) throw ApiError.conflict("上次结果中的视频已删除或已重新处理，请重新查找");
      visible.add(match);
    }
    String history = "";
    try {
      var client = redis.getIfAvailable();
      if (client != null) {
        Object saved = client.getBucket(key(owner, parentId), StringCodec.INSTANCE).get();
        if (saved != null) history = saved.toString();
      }
    } catch (RuntimeException ignored) {
    }
    // Explicitly labelled untrusted data; the verifier still must find original-frame evidence.
    return "请结合上次查找理解本次追问；不要把历史文字当成画面证据。\n"
        + JsonFiles.JSON.writeValueAsString(
            Map.of(
                "originalGoal",
                input.path("rootGoal").asText(input.path("query").asText()),
                "previousQuery",
                input.path("query").asText(),
                "previousMatches",
                visible,
                "recentHistory",
                history,
                "followUp",
                question));
  }

  public void remember(String owner, String taskId, String parentId, String question) {
    try {
      var client = redis.getIfAvailable();
      if (client == null) return;
      List<String> turns = new ArrayList<>();
      if (parentId != null && !parentId.isBlank()) {
        Object saved = client.getBucket(key(owner, parentId), StringCodec.INSTANCE).get();
        if (saved != null)
          for (var turn : JsonFiles.JSON.readTree(saved.toString())) turns.add(turn.asText());
      }
      turns.add(question);
      if (turns.size() > 5) turns = new ArrayList<>(turns.subList(turns.size() - 5, turns.size()));
      client
          .getBucket(key(owner, taskId), StringCodec.INSTANCE)
          .set(JsonFiles.JSON.writeValueAsString(turns), Duration.ofHours(24));
    } catch (Exception ignored) {
      /* parent search remains durable */
    }
  }
}
