package dev.videosearch.model;

import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.FrameSampler;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Local OCR supplements vision; its failure never fabricates readable text. */
@Service
public class OcrBranch {
  private final ThreadPoolTaskExecutor executor;
  private final String command;
  private final boolean enabled;
  private final Path cache;

  @Value("${VS_OCR_MODE:native}")
  private String mode;

  @Value("${VS_WSL_DISTRO:}")
  private String wslDistro = "";

  public OcrBranch(
      @Qualifier("ocrExecutor") ThreadPoolTaskExecutor executor,
      @Value("${TESSERACT_PATH:tesseract}") String command,
      @Value("${VS_OCR_ENABLED:false}") boolean enabled,
      @Value("${app.data-dir:.data}") String data) {
    this.executor = executor;
    this.command = command;
    this.enabled = enabled;
    cache = Path.of(data).resolve("ocr-cache");
  }

  public Map<String, Object> extract(List<FrameSampler.Frame> frames) throws Exception {
    if (!enabled) return Map.of("status", "DISABLED", "texts", List.of());
    Future<Map<String, Object>> work = executor.submit(() -> recognize(frames));
    try {
      return work.get(45, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      work.cancel(true);
      throw e;
    } catch (Exception e) {
      work.cancel(true);
      return Map.of("status", "FAILED", "texts", List.of(), "reason", "本地文字识别暂不可用，保留视觉描述");
    }
  }

  private Map<String, Object> recognize(List<FrameSampler.Frame> frames) throws Exception {
    List<Object> texts = new ArrayList<>();
    Long previous = null;
    for (var frame : frames) {
      long hash = differenceHash(frame.image());
      if (previous != null && Long.bitCount(previous ^ hash) <= 4) continue;
      previous = hash;
      String key = JsonFiles.hash(Files.readAllBytes(frame.image()));
      Path saved = cache.resolve(key + "-tesseract-chi_sim-eng-v1.json");
      String text;
      if (Files.exists(saved)) text = JsonFiles.read(saved).path("text").asText();
      else {
        Path out = Files.createTempFile("framelock-ocr-", ".txt"),
            err = Files.createTempFile("framelock-ocr-", ".log");
        Process process = null;
        try {
          List<String> args = new ArrayList<>();
          String image = frame.image().toAbsolutePath().toString();
          if ("wsl".equals(mode)) {
            image =
                "/mnt/"
                    + Character.toLowerCase(image.charAt(0))
                    + image.substring(2).replace('\\', '/');
            args.add("wsl.exe");
            if (!wslDistro.isBlank()) args.addAll(List.of("-d", wslDistro));
            args.add("--");
          }
          args.addAll(List.of(command, image, "stdout", "-l", "chi_sim+eng"));
          process =
              new ProcessBuilder(args)
                  .redirectOutput(out.toFile())
                  .redirectError(err.toFile())
                  .start();
          if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IllegalStateException("OCR timeout");
          if (process.exitValue() != 0) throw new IllegalStateException("OCR failed");
          text = Files.readString(out).trim();
          JsonFiles.write(saved, Map.of("text", text));
        } finally {
          if (process != null && process.isAlive()) process.destroyForcibly();
          Files.deleteIfExists(out);
          Files.deleteIfExists(err);
        }
      }
      if (!text.isBlank()) texts.add(Map.of("timestampMs", frame.timestampMs(), "text", text));
    }
    return Map.of("status", "READY", "texts", texts);
  }

  public static long differenceHash(Path path) throws Exception {
    var image = ImageIO.read(path.toFile());
    if (image == null) throw new IllegalArgumentException("Unreadable frame");
    long hash = 0;
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) {
        int a = image.getRGB(x * (image.getWidth() - 1) / 8, y * (image.getHeight() - 1) / 7),
            b = image.getRGB((x + 1) * (image.getWidth() - 1) / 8, y * (image.getHeight() - 1) / 7);
        int ga = ((a >> 16) & 255) * 299 + ((a >> 8) & 255) * 587 + (a & 255) * 114,
            gb = ((b >> 16) & 255) * 299 + ((b >> 8) & 255) * 587 + (b & 255) * 114;
        if (ga > gb) hash |= 1L << (y * 8 + x);
      }
    return hash;
  }
}
