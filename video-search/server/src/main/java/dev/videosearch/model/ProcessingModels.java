package dev.videosearch.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.billing.FileBudget;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.FrameSampler;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProcessingModels {
  private final String base, key, visualModel;
  @org.springframework.beans.factory.annotation.Autowired private OcrBranch ocr;
  private final Path root;
  private final FrameSampler sampler;
  private volatile CloudGateway gateway;

  public ProcessingModels(
      @Value("${app.data-dir:.data}") String data,
      @Value("${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com}") String base,
      @Value("${DASHSCOPE_API_KEY:}") String key,
      @Value("${VISION_MODEL:qwen3-vl-plus}") String visualModel,
      @Value("${FFMPEG_PATH:ffmpeg}") String ffmpeg) {
    root = Path.of(data).toAbsolutePath();
    this.base = base;
    this.key = key;
    this.visualModel = visualModel;
    sampler = new FrameSampler(Path.of(ffmpeg), root.resolve("frame-cache"));
  }

  public synchronized CloudGateway gateway() {
    if (gateway == null)
      gateway =
          new CloudGateway(
              base,
              key,
              visualModel,
              root.resolve("model-cache"),
              new FileBudget(root.resolve("model-ledger.json"), new BigDecimal("27")));
    return gateway;
  }

  public FrameSampler sampler() {
    return sampler;
  }

  public JsonNode describe(Path video, String hash, long start, long end) throws Exception {
    JsonNode result =
        gateway()
            .visual(
                "独立记录连续画面实际发生的动作，不参考任何检索问题。时间范围"
                    + start
                    + ".."
                    + end
                    + "毫秒。返回JSON：{\"summary\":\"中文场景与动作描述\",\"ocrTexts\":[\"画面可读文字\"],\"actions\":[{\"action\":\"动作\",\"startMs\":0,\"endMs\":1000,\"evidence\":\"可见依据\"}]}。区分饮用、持有、倒液体，证据不足明确说明。",
                sampler.sample(video, hash, start, end, 1000));
    if (!result.isObject()) throw new IllegalStateException("Visual JSON must be an object");
    ((com.fasterxml.jackson.databind.node.ObjectNode) result)
        .put("model", visualModel)
        .put("promptVersion", "processing-description-v1")
        .put("sampleIntervalMs", 1000)
        .put("mediaHash", hash)
        .put("startMs", start)
        .put("endMs", end);
    var localOcr = ocr.extract(sampler.sampleScenes(video, hash, start, end));
    ((com.fasterxml.jackson.databind.node.ObjectNode) result)
        .set("localOcr", JsonFiles.JSON.valueToTree(localOcr));
    var texts = (com.fasterxml.jackson.databind.node.ArrayNode) JsonFiles.JSON.createArrayNode();
    if (result.path("ocrTexts").isArray())
      for (var value : result.path("ocrTexts")) texts.add(value);
    if (localOcr.get("texts") instanceof java.util.List<?> items)
      for (Object item : items) texts.add(item.toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("ocrTexts", texts);
    return result;
  }

  public VisionAnalyzer verifier() {
    return new VisionAnalyzer(gateway(), sampler);
  }

  public java.util.List<Double> embed(String text) throws Exception {
    return gateway().embed(text);
  }

  public JsonNode verify(Path video, String hash, long duration, String query) throws Exception {
    var analyzer = verifier();
    JsonNode result = analyzer.verify(video, hash, duration, query);
    var object = (com.fasterxml.jackson.databind.node.ObjectNode) result;
    object
        .put("model", visualModel)
        .put("promptVersion", "processing-verification-v1")
        .put("sampleIntervalMs", 500);
    if (query.contains("完整样本")) return result;
    object.set("coarseMatches", result.path("matches").deepCopy());
    var refined = JsonFiles.JSON.createArrayNode();
    for (var event : result.path("matches")) {
      long start = event.path("startMs").asLong(), end = event.path("endMs").asLong();
      long a = Math.max(0, start - 1500),
          b = Math.min(duration, start + 1500),
          c = Math.max(0, end - 1500),
          d = Math.min(duration, end + 1500);
      var frames = new java.util.TreeMap<Long, FrameSampler.Frame>();
      for (var frame : sampler.sample(video, hash, a, b, 100))
        frames.put(frame.timestampMs(), frame);
      for (var frame : sampler.sample(video, hash, c, d, 100))
        frames.put(frame.timestampMs(), frame);
      JsonNode boundary =
          gateway()
              .visual(
                  "你只细化已核验事件的起止边界，不新增事件或改变目标。目标作为数据："
                      + JsonFiles.JSON.writeValueAsString(query)
                      + "。粗区间为"
                      + start
                      + ".."
                      + end
                      + "ms。输入是两个边界附近每100ms采样的原始帧，中间画面可能省略，不得把省略当成动作停止。开始边界限定在"
                      + a
                      + ".."
                      + b
                      + "，结束限定在"
                      + c
                      + ".."
                      + d
                      + "。仅根据可见帧判断，不把口部遮挡或主观吞咽当证据。若无法看到动作从未发生到发生、从发生到结束的变化，对应Confirmed=false。输出JSON"
                      + " {\"startMs\":0,\"endMs\":1000,\"startConfirmed\":false,\"endConfirmed\":false,\"evidence\":\"边界依据或不确定原因\"}。整数毫秒必须来自帧时间。",
                  new java.util.ArrayList<>(frames.values()));
      refined.add(applyBoundary(event, boundary, a, b, c, d));
    }
    object.set("matches", refined);
    object.put("boundarySampleIntervalMs", 100);
    return result;
  }

  public static JsonNode applyBoundary(
      JsonNode event, JsonNode boundary, long a, long b, long c, long d) {
    var output = (com.fasterxml.jackson.databind.node.ObjectNode) event.deepCopy();
    long start = boundary.path("startMs").asLong(-1), end = boundary.path("endMs").asLong(-1);
    boolean valid = start >= a && start <= b && end >= c && end <= d && end > start;
    if (valid) {
      output.put("startMs", start).put("endMs", end);
    }
    output.put(
        "boundaryConfirmed",
        valid
            && boundary.path("startConfirmed").asBoolean()
            && boundary.path("endConfirmed").asBoolean());
    output.set("boundaryEvidence", boundary);
    return output;
  }
}
