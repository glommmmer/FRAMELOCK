package dev.videosearch.lab;

import java.util.*;

public final class Evaluator {
  public record Event(String videoId, long startMs, long endMs) {
    public Event {
      if (videoId == null || startMs < 0 || endMs <= startMs)
        throw new IllegalArgumentException("Invalid event");
    }
  }

  public record CaseScore(
      int expected,
      int returned,
      int matched,
      double recall,
      double precision,
      boolean falsePositive,
      List<Long> startErrors,
      List<Long> endErrors) {}

  private Evaluator() {}

  public static double iou(Event a, Event b) {
    if (!a.videoId().equals(b.videoId())) return 0;
    long intersection =
        Math.max(0, Math.min(a.endMs(), b.endMs()) - Math.max(a.startMs(), b.startMs()));
    return (double) intersection
        / (Math.max(a.endMs(), b.endMs()) - Math.min(a.startMs(), b.startMs()));
  }

  public static CaseScore score(List<Event> expected, List<Event> predicted, int k) {
    if (k < 1) throw new IllegalArgumentException("Invalid k");
    boolean[] matched = new boolean[expected.size()];
    int count = 0;
    List<Long> starts = new ArrayList<>(), ends = new ArrayList<>();
    var top = predicted.stream().limit(k).toList();
    for (Event event : top) {
      int best = -1;
      double overlap = 0.499999999;
      for (int i = 0; i < expected.size(); i++)
        if (!matched[i] && iou(event, expected.get(i)) > overlap) {
          best = i;
          overlap = iou(event, expected.get(i));
        }
      if (best >= 0) {
        matched[best] = true;
        count++;
        starts.add(Math.abs(event.startMs() - expected.get(best).startMs()));
        ends.add(Math.abs(event.endMs() - expected.get(best).endMs()));
      }
    }
    return new CaseScore(
        expected.size(),
        top.size(),
        count,
        expected.isEmpty() ? 0 : (double) count / expected.size(),
        top.isEmpty() ? 0 : (double) count / top.size(),
        expected.isEmpty() && !top.isEmpty(),
        starts,
        ends);
  }

  public static double median(List<Long> values) {
    if (values.isEmpty()) return Double.NaN;
    var sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }
}
