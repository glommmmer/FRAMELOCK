package dev.videosearch.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.videosearch.lab.JsonFiles;
import org.junit.jupiter.api.Test;

class VisionAnalyzerTest {
  @Test
  void uncertainHardConditionRejectsOtherwisePositivePrediction() throws Exception {
    var raw =
        JsonFiles.JSON.readTree(
            "{\"conditions\":[{\"status\":\"uncertain\"}],\"matches\":[{\"startMs\":100,\"endMs\":900}]}");
    assertEquals(0, VisionAnalyzer.enforce(raw, 1000, false).path("matches").size());
  }

  @Test
  void fragmentCannotSatisfyWholeClipRequirement() throws Exception {
    var raw =
        JsonFiles.JSON.readTree(
            "{\"conditions\":[{\"status\":\"met\"}],\"matches\":[{\"startMs\":100,\"endMs\":900}]}");
    assertEquals(0, VisionAnalyzer.enforce(raw, 1000, true).path("matches").size());
    assertEquals(1, VisionAnalyzer.enforce(raw, 1000, false).path("matches").size());
  }

  @Test
  void fabricatedOutOfRangeTimeIsAnError() throws Exception {
    var raw =
        JsonFiles.JSON.readTree(
            "{\"conditions\":[{\"status\":\"met\"}],\"matches\":[{\"startMs\":100,\"endMs\":2000}]}");
    assertThrows(IllegalStateException.class, () -> VisionAnalyzer.enforce(raw, 1000, false));
  }

  @Test
  void wholeClipCanEndAtLastSampledFrameRatherThanPhysicalEnd() throws Exception {
    var raw =
        JsonFiles.JSON.readTree(
            "{\"conditions\":[{\"status\":\"met\"}],\"matches\":[{\"startMs\":0,\"endMs\":9952}]}");
    var event = VisionAnalyzer.enforce(raw, 10000, true).path("matches").path(0);
    assertEquals(10000, event.path("endMs").asLong());
  }

  @Test
  void absentWholeClipRequirementCannotVetoAMatchingSegment() throws Exception {
    var raw =
        JsonFiles.JSON.readTree(
            "{\"conditions\":[{\"condition\":\"饮用\",\"status\":\"met\"},{\"condition\":\"是否要求完整样本：未要求\",\"status\":\"n/a\"}],\"matches\":[{\"startMs\":100,\"endMs\":900}]}");
    assertEquals(1, VisionAnalyzer.enforce(raw, 1000, false).path("matches").size());
    assertEquals(0, VisionAnalyzer.enforce(raw, 1000, true).path("matches").size());
  }
}
