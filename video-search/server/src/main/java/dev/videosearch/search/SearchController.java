package dev.videosearch.search;

import dev.videosearch.api.ApiError;
import dev.videosearch.infra.RequestLimits;
import dev.videosearch.lab.JsonFiles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/searches")
public class SearchController {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final RequestLimits limits;
  private final SearchContext contexts;
  @org.springframework.beans.factory.annotation.Autowired private FollowUpContext followups;

  public SearchController(
      JdbcTemplate db, TransactionTemplate tx, RequestLimits limits, SearchContext contexts) {
    this.db = db;
    this.tx = tx;
    this.limits = limits;
    this.contexts = contexts;
  }

  public record Request(
      @NotBlank String libraryId,
      List<String> videoIds,
      @NotBlank @Size(max = 500) String query,
      @Pattern(regexp = "NORMAL|AGENT") String mode,
      String parentSearchId) {}

  @PostMapping
  ResponseEntity<?> create(Principal user, @Valid @RequestBody Request request) throws Exception {
    String owner = user.getName();
    if (db.queryForObject(
            "SELECT COUNT(*) FROM vs_library WHERE id=? AND owner=? AND deleted=FALSE",
            Long.class,
            request.libraryId(),
            owner)
        == 0) throw ApiError.missing();
    String rootGoal = request.query().trim();
    List<String> ids = request.videoIds() == null ? List.of() : request.videoIds();
    if (request.parentSearchId() != null && !request.parentSearchId().isBlank()) {
      var parent = followups.parent(owner, request.parentSearchId());
      var previous = JsonFiles.JSON.readTree(parent.get("input_json").toString());
      rootGoal = previous.path("rootGoal").asText(previous.path("query").asText());
      if (!previous.path("libraryId").asText().equals(request.libraryId()))
        throw ApiError.bad("追问需使用上次查找的项目");
      List<String> prior = new ArrayList<>();
      for (var video : previous.path("videoIds")) prior.add(video.asText());
      if (prior.isEmpty()) {
        var output = JsonFiles.JSON.readTree(parent.get("output_json").toString());
        for (var video : output.path("searchedVideos")) prior.add(video.path("videoId").asText());
        if (prior.isEmpty())
          for (var match : output.path("matches")) {
            String video = match.path("videoId").asText();
            if (!prior.contains(video)) prior.add(video);
          }
        if (prior.isEmpty()) throw ApiError.conflict("上次查找没有可继续使用的视频，请重新查找");
      }
      if (!ids.isEmpty() && !prior.isEmpty() && !prior.containsAll(ids))
        throw ApiError.bad("追问不能扩大上次的视频范围");
      if (ids.isEmpty()) ids = prior;
    }
    if (ids.size() > 10) throw ApiError.bad("最多选择10个视频");
    for (String id : ids)
      if (db.queryForObject(
              "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND library_id=? AND"
                  + " deleted=FALSE",
              Long.class,
              id,
              owner,
              request.libraryId())
          == 0) throw ApiError.missing();
    contexts.requireReady(owner, request.libraryId(), ids);
    limits.search(owner);
    String id = UUID.randomUUID().toString();
    String input =
        JsonFiles.JSON.writeValueAsString(
            Map.of(
                "libraryId",
                request.libraryId(),
                "videoIds",
                ids,
                "query",
                request.query().trim(),
                "mode",
                request.mode() == null ? "NORMAL" : request.mode(),
                "parentSearchId",
                request.parentSearchId() == null ? "" : request.parentSearchId(),
                "flowVersion",
                2,
                "rootGoal",
                rootGoal));
    tx.executeWithoutResult(
        status -> {
          db.update(
              "INSERT INTO vs_task(id,owner,kind,stage,status,input_json,idempotency_key)"
                  + " VALUES(?,?,'SEARCH','RETRIEVE','PENDING',?,?)",
              id,
              owner,
              input,
              id);
          db.update(
              "INSERT INTO vs_outbox(id,task_id) VALUES(?,?)", UUID.randomUUID().toString(), id);
        });
    return ResponseEntity.accepted().body(Map.of("taskId", id, "id", id, "status", "PENDING"));
  }

  @GetMapping("/{id}")
  Object get(Principal user, @PathVariable String id) throws Exception {
    var rows =
        db.queryForList(
            "SELECT id,status,stage,error_message,input_json,output_json FROM vs_task WHERE id=?"
                + " AND owner=? AND kind='SEARCH'",
            id,
            user.getName());
    if (rows.isEmpty()) throw ApiError.missing();
    var row = new LinkedHashMap<>(rows.getFirst());
    row.put("context", contexts.describe(user.getName(), row.remove("input_json")));
    Object output = row.remove("output_json");
    if (output != null) {
      var result = JsonFiles.JSON.readTree(output.toString());
      // A saved search cannot resurrect a deleted video's URL or evidence.
      if (result.path("matches").isArray()) {
        var kept = JsonFiles.JSON.createArrayNode();
        for (var match : result.path("matches"))
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM vs_video WHERE id=? AND owner=? AND deleted=FALSE",
                  Long.class,
                  match.path("videoId").asText(),
                  user.getName())
              > 0) kept.add(match);
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("matches", kept);
      }
      row.put("result", result);
    }
    return row;
  }
}
