package dev.videosearch.search;

import dev.videosearch.api.ApiError;
import dev.videosearch.lab.JsonFiles;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Restores the user-visible scope of an existing search without running it again. */
@Component
public class SearchContext {
  private final JdbcTemplate db;

  public SearchContext(JdbcTemplate db) {
    this.db = db;
  }

  public void requireReady(String owner, String projectId, List<?> videoIds) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE",
            Long.class,
            projectId,
            owner)
        == 0) throw ApiError.missing();
    var parameters = new ArrayList<Object>(List.of(owner, projectId));
    parameters.addAll(videoIds);
    String scope =
        videoIds.isEmpty()
            ? ""
            : " AND id IN (" + String.join(",", Collections.nCopies(videoIds.size(), "?")) + ")";
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_video WHERE owner=? AND library_id=? AND deleted=FALSE AND"
                + " active_version IS NOT NULL"
                + scope,
            Long.class,
            parameters.toArray())
        == 0) throw ApiError.conflict("还没有处理好的视频。请先上传视频，或等视频处理完成后再查找。");
  }

  public Map<String, Object> describe(String owner, Object input) {
    var result = new LinkedHashMap<String, Object>();
    if (input == null) return result;
    try {
      var json = JsonFiles.JSON.readTree(input.toString());
      if (json == null || !json.isObject()) return result;
      String id = json.path("libraryId").asText("");
      var projects =
          db.queryForList("SELECT name,deleted FROM vs_library WHERE id=? AND owner=?", id, owner);
      result.put("libraryId", id);
      result.put("query", json.path("query").asText(""));
      result.put("mode", json.path("mode").asText("NORMAL"));
      result.put("parentSearchId", json.path("parentSearchId").asText(""));
      result.put(
          "videoIds",
          JsonFiles.JSON.convertValue(
              json.path("videoIds").isArray()
                  ? json.path("videoIds")
                  : JsonFiles.JSON.createArrayNode(),
              List.class));
      result.put("libraryName", projects.isEmpty() ? "项目已不可用" : projects.getFirst().get("name"));
      result.put(
          "projectDeleted",
          projects.isEmpty()
              || Boolean.TRUE.equals(projects.getFirst().get("deleted"))
              || "1".equals(String.valueOf(projects.getFirst().get("deleted"))));
    } catch (java.io.IOException | IllegalArgumentException ignored) {
      // Legacy malformed inputs remain readable as a task, without inventing a search scope.
    }
    return result;
  }
}
