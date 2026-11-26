package dev.videosearch.search;

import java.util.*;

public final class Retrieval {
  public record Segment(
      String id,
      String videoId,
      long startMs,
      long endMs,
      String description,
      List<Double> embedding) {}

  private Retrieval() {}

  public static List<Segment> rank(
      List<Segment> segments, String query, List<Double> embedding, int limit) {
    List<Segment> semantic =
        segments.stream()
            .sorted(
                Comparator.comparingDouble((Segment s) -> cosine(s.embedding(), embedding))
                    .reversed())
            .limit(20)
            .toList();
    List<Segment> lexical =
        segments.stream()
            .sorted(
                Comparator.comparingDouble((Segment s) -> lexical(s.description(), query))
                    .reversed())
            .limit(20)
            .toList();
    Map<String, Double> scores = new HashMap<>();
    for (int i = 0; i < semantic.size(); i++)
      scores.merge(semantic.get(i).id(), 1.0 / (60 + i + 1), Double::sum);
    for (int i = 0; i < lexical.size(); i++)
      if (lexical(lexical.get(i).description(), query) > 0)
        scores.merge(lexical.get(i).id(), 1.0 / (60 + i + 1), Double::sum);
    // One candidate per clip in the initial small fixture; avoid crowding out other videos.
    LinkedHashMap<String, Segment> videos = new LinkedHashMap<>();
    segments.stream()
        .sorted(
            Comparator.comparingDouble((Segment s) -> scores.getOrDefault(s.id(), 0.0)).reversed())
        .forEach(s -> videos.putIfAbsent(s.videoId(), s));
    return videos.values().stream().limit(limit).toList();
  }

  public static double cosine(List<Double> left, List<Double> right) {
    if (left.isEmpty() || left.size() != right.size()) return 0;
    double dot = 0, a = 0, b = 0;
    for (int i = 0; i < left.size(); i++) {
      dot += left.get(i) * right.get(i);
      a += left.get(i) * left.get(i);
      b += right.get(i) * right.get(i);
    }
    return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
  }

  private static double lexical(String text, String query) {
    String normalized = text.toLowerCase(Locale.ROOT);
    String cleaned = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    double score = 0;
    for (int i = 0; i < cleaned.length() - 1; i++)
      if (normalized.contains(cleaned.substring(i, i + 2))) score++;
    return score / Math.max(1, cleaned.length() - 1);
  }
}
