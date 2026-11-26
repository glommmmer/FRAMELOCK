package dev.videosearch.search;

import java.util.*;

/** All time arithmetic is host-side, independent of model text. */
public final class EventIntervals {
  private EventIntervals() {}

  public static List<Map<String, Object>> merge(List<Map<String, Object>> events) {
    List<Map<String, Object>> result = new ArrayList<>();
    events.stream()
        .sorted(
            Comparator.comparing((Map<String, Object> e) -> e.get("videoId").toString())
                .thenComparing(e -> e.get("versionId").toString())
                .thenComparingLong(e -> ((Number) e.get("startMs")).longValue()))
        .forEach(
            event -> {
              var next = new LinkedHashMap<>(event);
              if (!result.isEmpty()) {
                var last = result.getLast();
                long start = ((Number) next.get("startMs")).longValue(),
                    end = ((Number) next.get("endMs")).longValue();
                if (last.get("videoId").equals(next.get("videoId"))
                    && last.get("versionId").equals(next.get("versionId"))
                    && start <= ((Number) last.get("endMs")).longValue()) {
                  last.put("endMs", Math.max(end, ((Number) last.get("endMs")).longValue()));
                  last.put(
                      "boundaryConfirmed",
                      Boolean.TRUE.equals(last.get("boundaryConfirmed"))
                          && Boolean.TRUE.equals(next.get("boundaryConfirmed")));
                  return;
                }
              }
              result.add(next);
            });
    return result.stream().limit(5).toList();
  }
}
