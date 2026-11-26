package dev.videosearch.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalTest {
  @Test
  void overlappingWindowsDoNotExcludeOtherVideoCandidates() {
    var segments =
        List.of(
            new Retrieval.Segment("a0", "a", 0, 10000, "喝饮料", List.of(1.0, 0.0)),
            new Retrieval.Segment("a1", "a", 5000, 15000, "喝饮料", List.of(1.0, 0.0)),
            new Retrieval.Segment("b0", "b", 0, 10000, "拿杯子", List.of(.9, .1)));
    var results = Retrieval.rank(segments, "喝饮料", List.of(1.0, 0.0), 2);
    assertEquals(2, results.size());
    assertNotEquals(results.get(0).videoId(), results.get(1).videoId());
  }
}
