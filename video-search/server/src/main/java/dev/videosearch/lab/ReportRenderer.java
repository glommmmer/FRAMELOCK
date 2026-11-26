package dev.videosearch.lab;

import java.nio.file.*;
import java.util.*;

public final class ReportRenderer {
  private ReportRenderer() {}

  public static void render(
      Path output, Path fixture, List<Lab.QueryResult> results, Map<String, Object> report)
      throws Exception {
    Path assets = output.resolve("clips");
    Files.createDirectories(assets);
    Map<String, String> names = new HashMap<>();
    for (var clip : JsonFiles.read(fixture.resolve("annotations.json")).path("clips")) {
      String id = clip.path("clipId").asText();
      Path source = fixture.resolve(clip.path("clipPath").asText());
      String filename = id + ".mp4";
      Files.copy(source, assets.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
      names.put(id, filename);
    }
    StringBuilder body =
        new StringBuilder(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\""
                + " content=\"width=device-width,initial-scale=1\"><title>视频检索真实评测</title><style>body{font:16px/1.7"
                + " system-ui;background:#11151c;color:#e8edf5;max-width:1000px;padding:24px;margin:auto}article,header{padding:20px;background:#1d2531;border-radius:10px;margin:16px"
                + " 0}video{width:100%;max-width:560px}small,p{color:#bec9d9}summary{cursor:pointer}h2{font-size:20px}</style><h1>视频检索真实评测</h1>");
    body.append("<header>开发集 · 固定核验 B 路径<br>Recall@5：")
        .append(report.get("recallAt5"))
        .append(" · 无答案误报：")
        .append(report.get("falsePositiveQueries"))
        .append(" / ")
        .append(report.get("negativeQueries"))
        .append("<p>下方为模型预测，不是人工标注。标注仍为约 0.6 秒精度的暂定边界；数据来自已查看的开发素材，不代表未知视频的准确率。</p></header>");
    for (var result : results) {
      body.append("<article><h2>")
          .append(escape(result.queryId() + " · " + result.query()))
          .append("</h2><p>耗时 ")
          .append(result.elapsedMs())
          .append(" ms</p>");
      if (result.hits().isEmpty()) body.append("<p>未找到通过核验的片段。</p>");
      for (var hit : result.hits()) {
        body.append("<p>")
            .append(escape(hit.videoId()))
            .append(" · ")
            .append(hit.startMs() / 1000.0)
            .append("—")
            .append(hit.endMs() / 1000.0)
            .append(" 秒<br>")
            .append(escape(hit.evidence()))
            .append("</p>");
        String file = names.get(hit.videoId());
        if (file != null)
          body.append("<video controls muted preload=\"none\" src=\"clips/")
              .append(file)
              .append("#t=")
              .append(hit.startMs() / 1000.0)
              .append(",")
              .append(hit.endMs() / 1000.0)
              .append("\"></video>");
      }
      body.append("<details><summary>候选核验记录</summary><pre style=\"white-space:pre-wrap\">")
          .append(escape(JsonFiles.JSON.writeValueAsString(result.trace())))
          .append("</pre></details></article>");
    }
    Files.writeString(output.resolve("review.html"), body.append("</html>").toString());
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
