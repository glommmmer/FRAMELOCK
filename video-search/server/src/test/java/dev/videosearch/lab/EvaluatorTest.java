package dev.videosearch.lab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluatorTest {
  private static Evaluator.Event event(String video, long start, long end) {
    return new Evaluator.Event(video, start, end);
  }

  @Test
  void repeatedPredictionsCannotInflateRecall() {
    var expected = List.of(event("v", 100, 300), event("v", 500, 700));
    var score = Evaluator.score(expected, List.of(event("v", 100, 300), event("v", 100, 300)), 5);
    assertEquals(1, score.matched());
    assertEquals(.5, score.recall());
    assertEquals(.5, score.precision());
  }

  @Test
  void matchingTimeInWrongVideoIsNotAHit() {
    assertEquals(
        0,
        Evaluator.score(List.of(event("a", 0, 1000)), List.of(event("b", 0, 1000)), 5).matched());
  }

  @Test
  void negativeQueryAndEmptyPositiveResultsAreReportedCorrectly() {
    assertTrue(Evaluator.score(List.of(), List.of(event("v", 0, 1000)), 5).falsePositive());
    var score = Evaluator.score(List.of(event("v", 0, 1000)), List.of(), 5);
    assertEquals(0, score.recall());
    assertEquals(0, score.precision());
  }

  @Test
  void topKAndTemporalThresholdAreEnforced() {
    var expected = List.of(event("v", 0, 1000));
    assertEquals(1, Evaluator.score(expected, List.of(event("v", 0, 500)), 1).matched());
    assertEquals(
        0,
        Evaluator.score(expected, List.of(event("v", 0, 499), event("v", 0, 1000)), 1).matched());
  }
}
