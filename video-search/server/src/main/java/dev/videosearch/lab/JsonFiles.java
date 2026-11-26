package dev.videosearch.lab;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class JsonFiles {
  public static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private JsonFiles() {}

  public static JsonNode read(Path path) throws IOException {
    return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
  }

  public static void write(Path path, Object value) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "write-", ".tmp");
    try {
      Files.writeString(temporary, JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static JsonNode modelJson(String text) throws IOException {
    String cleaned =
        text.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    return JSON.readTree(cleaned);
  }
}
