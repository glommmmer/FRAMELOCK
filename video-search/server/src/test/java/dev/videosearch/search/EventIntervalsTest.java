package dev.videosearch.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EventIntervalsTest {
  private Map<String, Object> event(String video, String version, long start, long end) {
    return Map.of(
        "videoId",
        video,
        "versionId",
        version,
        "startMs",
        start,
        "endMs",
        end,
        "boundaryConfirmed",
        true);
  }

  @Test
  void overlappingWindowsMergeButSeparateEventsAndVersionsDoNot() {
    var result =
        EventIntervals.merge(
            List.of(
                event("v", "a", 1000, 3000),
                event("v", "a", 2500, 4500),
                event("v", "a", 6000, 7000),
                event("v", "b", 1000, 3000),
                event("other", "a", 1000, 3000)));
    assertEquals(4, result.size());
    var merged =
        result.stream()
            .filter(
                e ->
                    e.get("videoId").equals("v")
                        && e.get("versionId").equals("a")
                        && ((Number) e.get("startMs")).longValue() == 1000)
            .findFirst()
            .orElseThrow();
    assertEquals(4500L, merged.get("endMs"));
  }
}
