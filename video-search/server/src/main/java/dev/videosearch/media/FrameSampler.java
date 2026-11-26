package dev.videosearch.media;

import dev.videosearch.lab.JsonFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class FrameSampler {
  public record Frame(long timestampMs, Path image) {}

  private final Path executable;
  private final Path cache;
  private final Semaphore slots = MediaProcesses.SLOT;

  public FrameSampler(Path executable, Path cache) {
    this.executable = executable;
    this.cache = cache;
  }

  public List<Frame> sample(Path video, String mediaHash, long startMs, long endMs, long intervalMs)
      throws Exception {
    return sampleInternal(video, mediaHash, startMs, endMs, intervalMs, false);
  }

  public List<Frame> sampleScenes(Path video, String mediaHash, long startMs, long endMs)
      throws Exception {
    return sampleInternal(video, mediaHash, startMs, endMs, 30000, true);
  }

  private List<Frame> sampleInternal(
      Path video, String mediaHash, long startMs, long endMs, long intervalMs, boolean scenes)
      throws Exception {
    if (startMs < 0 || endMs <= startMs || intervalMs < 100)
      throw new IllegalArgumentException("Invalid sampling range");
    String key =
        JsonFiles.hash(
            mediaHash
                + (scenes ? ":scenes-v1:" : ":frames-v3:")
                + startMs
                + ":"
                + endMs
                + ":"
                + intervalMs);
    Path folder = cache.resolve(key);
    Path manifest = folder.resolve("frames.json");
    if (Files.exists(manifest)) return load(folder, manifest);
    slots.acquire();
    try {
      if (Files.exists(manifest)) return load(folder, manifest);
      Files.createDirectories(folder);
      double start = startMs / 1000.0;
      double length = (endMs - startMs) / 1000.0;
      String filter =
          "trim=duration="
              + length
              + ",select=isnan(prev_selected_t)+gte(t-prev_selected_t\\,"
              + intervalMs / 1000.0
              + "),scale=640:640:force_original_aspect_ratio=decrease:force_divisible_by=2,showinfo";
      if (scenes)
        filter =
            "trim=duration="
                + length
                + ",select=isnan(prev_selected_t)+gt(scene\\,0.3)+gte(t-prev_selected_t\\,30),scale=640:640:force_original_aspect_ratio=decrease:force_divisible_by=2,showinfo";
      Path log = folder.resolve("ffmpeg.log");
      Process process =
          new ProcessBuilder(
                  executable.toString(),
                  "-hide_banner",
                  "-nostdin",
                  "-y",
                  "-ss",
                  Double.toString(start),
                  "-i",
                  video.toString(),
                  "-an",
                  "-vf",
                  filter,
                  "-fps_mode",
                  "vfr",
                  "-q:v",
                  "3",
                  folder.resolve("frame_%05d.jpg").toString())
              .redirectError(log.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        if (!process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS))
          throw new IOException("FFmpeg timeout");
        if (process.exitValue() != 0)
          throw new IOException("FFmpeg failed; see local sampling log");
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(3, TimeUnit.SECONDS);
        }
      }
      var matcher =
          Pattern.compile("\\bn:\\s*\\d+\\s+pts:.*?\\bpts_time:([\\d.eE+-]+)")
              .matcher(Files.readString(log));
      List<Long> stamps = new ArrayList<>();
      while (matcher.find())
        stamps.add(startMs + Math.round(Double.parseDouble(matcher.group(1)) * 1000));
      List<Path> images;
      try (var files = Files.list(folder)) {
        images =
            files
                .filter(p -> p.getFileName().toString().matches("frame_\\d+\\.jpg"))
                .sorted()
                .toList();
      }
      if (images.isEmpty() || images.size() != stamps.size())
        throw new IOException("Frame/timestamp mismatch");
      List<Map<String, Object>> entries = new ArrayList<>();
      for (int i = 0; i < images.size(); i++) {
        if (stamps.get(i) < startMs || stamps.get(i) >= endMs)
          throw new IOException("Frame outside requested range");
        entries.add(
            Map.of("timestampMs", stamps.get(i), "image", images.get(i).getFileName().toString()));
      }
      JsonFiles.write(manifest, entries);
      return load(folder, manifest);
    } finally {
      slots.release();
    }
  }

  private List<Frame> load(Path folder, Path manifest) throws IOException {
    List<Frame> result = new ArrayList<>();
    for (var frame : JsonFiles.read(manifest)) {
      Path image = folder.resolve(frame.path("image").asText());
      if (!Files.isRegularFile(image)) throw new IOException("Cached frame missing");
      result.add(new Frame(frame.path("timestampMs").asLong(), image));
    }
    return result;
  }
}
