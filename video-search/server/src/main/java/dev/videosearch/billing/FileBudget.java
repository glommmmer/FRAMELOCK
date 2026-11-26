package dev.videosearch.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.videosearch.lab.JsonFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

/** Persistent M0 ledger. A file lock protects independent CLI processes too. */
public final class FileBudget {
  private final Path path;
  private final BigDecimal stopAt;

  public FileBudget(Path path, BigDecimal stopAt) {
    this.path = path;
    this.stopAt = stopAt;
  }

  private ArrayNode records() throws IOException {
    return Files.exists(path) ? (ArrayNode) JsonFiles.read(path) : JsonFiles.JSON.createArrayNode();
  }

  private <T> T locked(Operation<T> operation) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    synchronized (FileBudget.class) {
      try (FileChannel channel =
              FileChannel.open(
                  path.resolveSibling(path.getFileName() + ".lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = channel.lock()) {
        return operation.run();
      }
    }
  }

  public String reserve(String kind, BigDecimal maximum) throws IOException {
    if (maximum.signum() <= 0) throw new IllegalArgumentException("Reservation must be positive");
    return locked(
        () -> {
          ArrayNode records = records();
          BigDecimal total = BigDecimal.ZERO;
          for (JsonNode record : records)
            total = total.add(record.path("chargedOrReservedYuan").decimalValue());
          if (total.add(maximum).compareTo(stopAt) > 0)
            throw new IllegalStateException("BUDGET_STOP: no additional model calls permitted");
          String id = UUID.randomUUID().toString();
          records
              .addObject()
              .put("id", id)
              .put("kind", kind)
              .put("status", "RESERVED")
              .put("createdAt", Instant.now().toString())
              .put("maximumReservedYuan", maximum)
              .put("chargedOrReservedYuan", maximum);
          JsonFiles.write(path, records);
          return id;
        });
  }

  public void settle(String id, BigDecimal actual, long input, long output, String providerId)
      throws IOException {
    if (actual.signum() < 0) throw new IllegalArgumentException("Negative charge");
    locked(
        () -> {
          ArrayNode records = records();
          boolean found = false;
          for (JsonNode record : records)
            if (record.path("id").asText().equals(id)) {
              var object = (com.fasterxml.jackson.databind.node.ObjectNode) record;
              object
                  .put("status", "ACCOUNTED")
                  .put("chargedOrReservedYuan", actual)
                  .put("inputTokens", input)
                  .put("outputTokens", output)
                  .put("providerId", providerId);
              found = true;
            }
          if (!found) throw new IllegalStateException("Unknown reservation");
          JsonFiles.write(path, records);
          return null;
        });
  }

  public void unknown(String id) throws IOException {
    locked(
        () -> {
          ArrayNode records = records();
          for (JsonNode record : records)
            if (record.path("id").asText().equals(id))
              ((com.fasterxml.jackson.databind.node.ObjectNode) record).put("status", "UNKNOWN");
          JsonFiles.write(path, records);
          return null;
        });
  }

  public JsonNode summary() throws IOException {
    return locked(
        () -> {
          ArrayNode records = records();
          BigDecimal total = BigDecimal.ZERO;
          int unknown = 0;
          for (JsonNode record : records) {
            total = total.add(record.path("chargedOrReservedYuan").decimalValue());
            if (!record.path("status").asText().equals("ACCOUNTED")) unknown++;
          }
          return JsonFiles.JSON
              .createObjectNode()
              .put("stopAtYuan", stopAt)
              .put("accountedOrReservedYuan", total)
              .put("calls", records.size())
              .put("unsettledCalls", unknown);
        });
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run() throws IOException;
  }
}
