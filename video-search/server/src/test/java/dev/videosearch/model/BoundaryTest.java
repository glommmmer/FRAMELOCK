package dev.videosearch.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.videosearch.lab.JsonFiles;
import org.junit.jupiter.api.Test;

class BoundaryTest {
  @Test
  void inventedOutOfRangeBoundaryCannotReplaceVerifiedCoarseEvent() throws Exception {
    var event =
        JsonFiles.JSON.readTree("{\"startMs\":2000,\"endMs\":4000,\"evidence\":\"visible\"}");
    var bad =
        JsonFiles.JSON.readTree(
            "{\"startMs\":0,\"endMs\":20000,\"startConfirmed\":true,\"endConfirmed\":true}");
    var result = ProcessingModels.applyBoundary(event, bad, 1000, 3000, 3000, 5000);
    assertEquals(2000, result.path("startMs").asLong());
    assertEquals(4000, result.path("endMs").asLong());
    assertFalse(result.path("boundaryConfirmed").asBoolean());
  }
}
