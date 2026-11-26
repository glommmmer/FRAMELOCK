package dev.videosearch.media;

import dev.videosearch.lab.JsonFiles;
import dev.videosearch.storage.ObjectStorage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaCache {
  private final Path root;
  private final ObjectStorage storage;
  private final String ffmpeg;
  private final Object[] locks = new Object[64];

  public MediaCache(
      ObjectStorage storage,
      @Value("${app.data-dir:.data}") String data,
      @Value("${FFMPEG_PATH:ffmpeg}") String ffmpeg)
      throws IOException {
    this.storage = storage;
    this.ffmpeg = ffmpeg;
    root = Path.of(data).resolve("media").toAbsolutePath();
    Files.createDirectories(root);
    for (int i = 0; i < locks.length; i++) locks[i] = new Object();
  }

  public Path video(String key, String hash) throws Exception {
    if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid media hash");
    Path target = root.resolve(hash + ".mp4");
    synchronized (locks[Math.floorMod(hash.hashCode(), locks.length)]) {
      if (Files.exists(target)) return target;
      Path temporary = Files.createTempFile(root, "fetch-", ".mp4");
      try {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = storage.open(key);
            OutputStream output = Files.newOutputStream(temporary)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = input.read(buffer)) != -1) {
            digest.update(buffer, 0, n);
            output.write(buffer, 0, n);
          }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hash))
          throw new IOException("Stored media hash mismatch");
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  public Path clip(Path original, String hash, long start, long end) throws Exception {
    if (start < 0 || end <= start || end - start > 60000)
      throw new IllegalArgumentException("Inspection clip must be within 60 seconds");
    Path target =
        root.resolve(JsonFiles.hash(hash + ":" + start + ":" + end + ":clip-v1") + ".mp4");
    synchronized (locks[Math.floorMod(target.hashCode(), locks.length)]) {
      if (Files.exists(target)) return target;
      Path temporary = Files.createTempFile(root, "clip-", ".mp4");
      try {
        execute(
            new String[] {
              "-ss",
              Double.toString(start / 1000.0),
              "-i",
              original.toString(),
              "-t",
              Double.toString((end - start) / 1000.0),
              "-an",
              "-c:v",
              "libx264",
              "-preset",
              "fast",
              "-crf",
              "23",
              "-movflags",
              "+faststart",
              temporary.toString()
            });
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  public Path audio(Path original, String hash) throws Exception {
    Path target = root.resolve(hash + ".wav");
    synchronized (locks[Math.floorMod(hash.hashCode(), locks.length)]) {
      if (Files.exists(target)) return target;
      Path temporary = Files.createTempFile(root, "audio-", ".wav");
      try {
        execute(
            new String[] {
              "-i", original.toString(), "-vn", "-ac", "1", "-ar", "16000", temporary.toString()
            });
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private void execute(String[] arguments) throws Exception {
    java.util.List<String> command =
        new java.util.ArrayList<>(java.util.List.of(ffmpeg, "-hide_banner", "-nostdin", "-y"));
    command.addAll(java.util.List.of(arguments));
    MediaProcesses.SLOT.acquire();
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IOException("Media subprocess failed or timed out");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      MediaProcesses.SLOT.release();
    }
  }
}
