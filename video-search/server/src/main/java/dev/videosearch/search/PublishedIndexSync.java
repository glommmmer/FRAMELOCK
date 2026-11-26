package dev.videosearch.search;

import dev.videosearch.lab.JsonFiles;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rebuilds the new collection from existing embeddings, without any model calls. */
@Component
@ConditionalOnProperty(name = "app.index-mode", havingValue = "qdrant")
public class PublishedIndexSync {
  private final JdbcTemplate db;
  private final QdrantIndex index;

  public PublishedIndexSync(JdbcTemplate db, QdrantIndex index) {
    this.db = db;
    this.index = index;
  }

  @Scheduled(fixedDelay = 5000)
  public void sync() {
    try {
      var videos =
          db.queryForList(
              "SELECT * FROM vs_video v WHERE deleted=FALSE AND active_version IS NOT NULL AND"
                  + " index_execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM vs_index_sync s"
                  + " WHERE s.video_id=v.id AND s.version_id=v.active_version AND"
                  + " s.execution_id=v.index_execution_id) LIMIT 1");
      if (videos.isEmpty()) return;
      var video = videos.getFirst();
      index.ensureCollection();
      var points = new ArrayList<QdrantIndex.Point>();
      for (var row :
          db.queryForList(
              "SELECT * FROM vs_segment WHERE video_id=? AND version_id=?",
              video.get("id"),
              video.get("active_version"))) {
        List<Double> vector = new ArrayList<>();
        for (var value : JsonFiles.JSON.readTree(row.get("embedding").toString()))
          vector.add(value.asDouble());
        if (vector.size() != 1024)
          throw new IllegalStateException("Stored embedding dimension differs from collection");
        points.add(
            new QdrantIndex.Point(
                row.get("id").toString(),
                video.get("id").toString(),
                video.get("active_version").toString(),
                video.get("owner").toString(),
                video.get("library_id").toString(),
                ((Number) row.get("start_ms")).longValue(),
                ((Number) row.get("end_ms")).longValue(),
                vector));
      }
      if (points.isEmpty()) return;
      for (int i = 0; i < points.size(); i += 32)
        index.upsert(
            points.subList(i, Math.min(points.size(), i + 32)),
            video.get("index_execution_id").toString());
      db.update(
          "INSERT INTO vs_index_sync(video_id,version_id,execution_id) SELECT"
              + " id,active_version,index_execution_id FROM vs_video WHERE id=? AND deleted=FALSE"
              + " AND active_version=? AND index_execution_id=?",
          video.get("id"),
          video.get("active_version"),
          video.get("index_execution_id"));
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .debug("Published index synchronization deferred: {}", error.getClass().getSimpleName());
    }
  }
}
