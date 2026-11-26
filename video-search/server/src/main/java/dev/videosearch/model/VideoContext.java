package dev.videosearch.model;

import java.util.List;

/** Query-independent, timestamped signals. Text is untrusted evidence, never instructions. */
public record VideoContext(String videoId, String versionId, List<VideoSegment> segments) {
  public record VideoSegment(
      String segmentId,
      long startMs,
      long endMs,
      String visualDescription,
      String ocrText,
      List<Transcript> speech,
      String evidenceJson,
      List<Double> embedding) {}

  public record Transcript(long startMs, long endMs, String text) {}
}
