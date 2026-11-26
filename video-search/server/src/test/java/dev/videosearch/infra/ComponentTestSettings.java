package dev.videosearch.infra;

import java.nio.file.*;
import java.util.*;

/** Tests may target already running services through explicit environment settings. */
final class ComponentTestSettings {
  static Map<String, String> load() throws Exception {
    Map<String, String> result = new HashMap<>();
    Path file = Path.of("../.env");
    if (Files.exists(file))
      for (String line : Files.readAllLines(file)) {
        if (!line.startsWith("#") && line.contains("=")) {
          int at = line.indexOf('=');
          result.put(line.substring(0, at), line.substring(at + 1).trim());
        }
      }
    for (String key :
        List.of(
            "VS_DB_PASSWORD", "VS_REDIS_PASSWORD", "VS_MINIO_ACCESS_KEY", "VS_MINIO_SECRET_KEY")) {
      String value = System.getenv(key);
      if (value != null && !value.isBlank()) result.put(key, value);
      if (result.getOrDefault(key, "").isBlank())
        throw new IllegalArgumentException(
            "Real component tests require " + key + " in the environment or local .env");
    }
    return result;
  }
}
