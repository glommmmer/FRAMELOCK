package dev.videosearch.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import dev.videosearch.lab.JsonFiles;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameSamplerTest {
  @TempDir Path folder;

  @Test
  void nonzeroSeekKeepsFrameTimesAndDoesNotPublishTrimmedFrames() throws Exception {
    String executable = System.getenv("FFMPEG_PATH");
    assumeTrue(
        executable != null && Files.isRegularFile(Path.of(executable)), "FFMPEG_PATH required");
    Path video = folder.resolve("colors.mp4");
    var p =
        new ProcessBuilder(
                executable,
                "-hide_banner",
                "-nostdin",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=160x90:r=10:d=1",
                "-f",
                "lavfi",
                "-i",
                "color=c=blue:s=160x90:r=10:d=2",
                "-filter_complex",
                "[0:v][1:v]concat=n=2:v=1:a=0[v]",
                "-map",
                "[v]",
                "-c:v",
                "libx264",
                video.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, p.exitValue());
    String hash = JsonFiles.hash(Files.readAllBytes(video));
    var sampler = new FrameSampler(Path.of(executable), folder.resolve("frames"));
    var frames = sampler.sample(video, hash, 800, 1600, 200);
    assertTrue(frames.size() >= 3);
    for (var frame : frames) {
      assertTrue(frame.timestampMs() >= 800 && frame.timestampMs() < 1600);
      int pixel = ImageIO.read(frame.image().toFile()).getRGB(20, 20);
      int red = (pixel >> 16) & 255, blue = pixel & 255;
      if (frame.timestampMs() < 1000) assertTrue(red > blue + 100);
      else assertTrue(blue > red + 100);
    }
    assertEquals(hash, JsonFiles.hash(Files.readAllBytes(video)));
    assertEquals(frames, sampler.sample(video, hash, 800, 1600, 200));
  }
}
