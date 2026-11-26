package dev.videosearch.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.model.ProcessingModels;
import java.nio.file.Path;

/** The visual model reviews original frames; deterministic bounds cannot be waived by a model. */
public final class AgentCritic {
  private final ProcessingModels models;

  public AgentCritic(ProcessingModels models) {
    this.models = models;
  }

  public JsonNode review(Path clip, String hash, long duration, String target) throws Exception {
    JsonNode review = models.verify(clip, hash, duration, target);
    if (!review.path("matches").isArray())
      throw new IllegalStateException("Critic omitted structured matches");
    for (var event : review.path("matches")) {
      if (!event.path("startMs").isIntegralNumber()
          || !event.path("endMs").isIntegralNumber()
          || event.path("startMs").asLong() < 0
          || event.path("endMs").asLong() <= event.path("startMs").asLong()
          || event.path("endMs").asLong() > duration
          || event.path("evidence").asText().isBlank())
        throw new IllegalStateException("Critic returned invalid timestamp evidence");
    }
    return review;
  }
}
