package dev.videosearch.lab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JsonFilesTest {
  @Test
  void cacheFingerprintDoesNotDependOnMapInsertionOrder() throws Exception {
    Map<String, Object> first = new LinkedHashMap<>(), second = new LinkedHashMap<>();
    first.put("model", "vision");
    first.put("messages", List.of(Map.of("role", "user", "content", "x")));
    second.put("messages", List.of(Map.of("content", "x", "role", "user")));
    second.put("model", "vision");
    assertArrayEquals(
        JsonFiles.JSON.writeValueAsBytes(first), JsonFiles.JSON.writeValueAsBytes(second));
  }
}
