package dev.videosearch.billing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBudgetTest {
  @TempDir Path folder;

  @Test
  void unknownCallsKeepReservationAcrossInstances() throws Exception {
    Path path = folder.resolve("ledger.json");
    var first = new FileBudget(path, new BigDecimal("1"));
    String id = first.reserve("VISUAL", new BigDecimal("0.7"));
    first.unknown(id);
    var reopened = new FileBudget(path, BigDecimal.ONE);
    assertThrows(
        IllegalStateException.class, () -> reopened.reserve("VISUAL", new BigDecimal("0.4")));
    assertEquals(1, reopened.summary().path("unsettledCalls").asInt());
  }

  @Test
  void settlementReleasesUnusedReservation() throws Exception {
    var budget = new FileBudget(folder.resolve("ledger.json"), BigDecimal.ONE);
    String id = budget.reserve("VISUAL", new BigDecimal("0.9"));
    budget.settle(id, new BigDecimal("0.1"), 100, 10, "provider");
    assertDoesNotThrow(() -> budget.reserve("VISUAL", new BigDecimal("0.8")));
  }

  @Test
  void concurrentReservationsCannotOverspend() throws Exception {
    var budget = new FileBudget(folder.resolve("ledger.json"), BigDecimal.ONE);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                try {
                  budget.reserve("VISUAL", new BigDecimal("0.7"));
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
      var b =
          pool.submit(
              () -> {
                try {
                  budget.reserve("VISUAL", new BigDecimal("0.7"));
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
      assertNotEquals(a.get(), b.get());
    }
  }
}
