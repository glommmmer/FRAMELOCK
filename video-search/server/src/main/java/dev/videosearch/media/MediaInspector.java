package dev.videosearch.media;

import dev.videosearch.api.ApiError;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaInspector {
  private final String executable;

  public MediaInspector(@Value("${FFMPEG_PATH:ffmpeg}") String executable) {
    this.executable = executable;
  }

  public record Info(long durationMs, boolean hasAudio) {}

  public Info inspect(Path path) throws Exception {
    Path log = Files.createTempFile(path.getParent(), "probe-", ".log");
    Process process = null;
    MediaProcesses.SLOT.acquire();
    try {
      process =
          new ProcessBuilder(executable, "-hide_banner", "-nostdin", "-i", path.toString())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS)) throw ApiError.bad("视频信息读取超时");
      String text = Files.readString(log);
      var duration = Pattern.compile("Duration: (\\d+):(\\d+):([\\d.]+)").matcher(text);
      if (!duration.find() || !text.contains("Video: h264") || !text.contains("mov,mp4,m4a"))
        throw ApiError.bad("首版支持可识别的 H.264 MP4 视频");
      long ms =
          Math.round(
              (Long.parseLong(duration.group(1)) * 3600
                      + Long.parseLong(duration.group(2)) * 60
                      + Double.parseDouble(duration.group(3)))
                  * 1000);
      if (ms <= 0 || ms > 1800000) throw ApiError.bad("视频时长必须在30分钟以内");
      return new Info(ms, text.contains("Audio:"));
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      MediaProcesses.SLOT.release();
      Files.deleteIfExists(log);
    }
  }
}
