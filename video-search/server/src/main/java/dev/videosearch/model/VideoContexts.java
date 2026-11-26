package dev.videosearch.model;

import dev.videosearch.lab.JsonFiles;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VideoContexts {
  private final JdbcTemplate db;

  public VideoContexts(JdbcTemplate db) {
    this.db = db;
  }

  public VideoContext load(String owner, String video, String version) throws Exception {
    var segments = new ArrayList<VideoContext.VideoSegment>();
    var speech =
        db.queryForList(
            "SELECT start_ms,end_ms,content FROM vs_transcript WHERE video_id=? AND version_id=?"
                + " ORDER BY start_ms",
            video,
            version);
    for (var row :
        db.queryForList(
            "SELECT * FROM vs_segment WHERE owner=? AND video_id=? AND version_id=? ORDER BY"
                + " start_ms",
            owner,
            video,
            version)) {
      long start = ((Number) row.get("start_ms")).longValue(),
          end = ((Number) row.get("end_ms")).longValue();
      var cues = new ArrayList<VideoContext.Transcript>();
      for (var cue : speech) {
        long a = ((Number) cue.get("start_ms")).longValue(),
            b = ((Number) cue.get("end_ms")).longValue();
        if (a < end && b > start)
          cues.add(new VideoContext.Transcript(a, b, cue.get("content").toString()));
      }
      List<Double> vector = new ArrayList<>();
      for (var value : JsonFiles.JSON.readTree(row.get("embedding").toString()))
        vector.add(value.asDouble());
      segments.add(
          new VideoContext.VideoSegment(
              row.get("id").toString(),
              start,
              end,
              String.valueOf(row.get("description")),
              String.valueOf(row.get("ocr_text")),
              cues,
              String.valueOf(row.get("evidence_json")),
              vector));
    }
    return new VideoContext(video, version, segments);
  }
}
