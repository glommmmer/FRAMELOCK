package dev.videosearch.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.videosearch.lab.JsonFiles;
import dev.videosearch.media.FrameSampler;
import java.nio.file.Path;
import java.util.*;

/** Separates visual evidence inspection from candidate ranking and gold labels. */
public final class VisionAnalyzer {
  private final CloudGateway gateway;
  private final FrameSampler sampler;

  public VisionAnalyzer(CloudGateway gateway, FrameSampler sampler) {
    this.gateway = gateway;
    this.sampler = sampler;
  }

  public JsonNode verify(Path video, String hash, long duration, String query) throws Exception {
    String instruction =
        "你在严格审核素材是否精确满足检索条件，不需要努力给出匹配。用户目标："
            + JsonFiles.JSON.writeValueAsString(query)
            + "。先拆解目标中的硬条件（动作、对象/容器形状、否定条件、是否要求完整样本），逐项独立检查。"
            + "不能放宽用户的词义：瓶口不是杯口，往特定容器倒液体不能匹配另一种容器。相关但不符合任一硬条件就拒绝。"
            + "不要添加用户没要求的额外条件。包装可见时可以按标签和常识判断商品类别；‘汽水’是日常饮料类别，不要求检验化学成分。容器靠近脸、闻杯子、嘴被杯遮挡、说话或打嗝，不足以证明饮用。不要臆测吞咽或微弱液面变化；不清楚记"
            + " uncertain。若目标要求整个样本没有某动作，必须检查全段，不能只返回其中满足条件的一小段。返回"
            + " {\"conditions\":[{\"condition\":\"硬条件\",\"status\":\"met或unmet或uncertain\",\"evidence\":\"画面依据\"}],"
            + "\"matches\":[{\"startMs\":0,\"endMs\":1000,\"evidence\":\"可见依据\"}],\"explanation\":\"结论\"}。只有所有硬条件都为"
            + " met 才能有 matches，否则 matches=[]。必须使用给定帧时间的整数毫秒，范围0.."
            + duration
            + "。只输出JSON。";
    JsonNode raw = gateway.visual(instruction, sampler.sample(video, hash, 0, duration, 500));
    return enforce(raw, duration, query.contains("完整样本"));
  }

  public static JsonNode enforce(JsonNode raw, long duration, boolean wholeClip) {
    if (!raw.path("conditions").isArray()
        || raw.path("conditions").isEmpty()
        || !raw.path("matches").isArray())
      throw new IllegalStateException("Verifier omitted condition checks");
    var result = raw.deepCopy();
    var object = (com.fasterxml.jackson.databind.node.ObjectNode) result;
    boolean allMet = true;
    for (var condition : raw.path("conditions")) {
      // Scope is a server-side input constraint, not an extra requirement when absent.
      if (!wholeClip && condition.path("condition").asText().contains("完整样本")) continue;
      if (!condition.path("status").asText().equals("met")) allMet = false;
    }
    object.set("rawMatches", raw.path("matches").deepCopy());
    var accepted = JsonFiles.JSON.createArrayNode();
    if (allMet)
      for (var event : raw.path("matches")) {
        long start = event.path("startMs").asLong(-1), end = event.path("endMs").asLong(-1);
        if (start < 0 || end <= start || end > duration)
          throw new IllegalStateException("Verifier time outside inspected evidence");
        long tolerance = Math.min(600, duration / 20);
        if (!wholeClip) accepted.add(event);
        else if (start <= tolerance && end >= duration - tolerance) {
          var whole = event.deepCopy();
          ((com.fasterxml.jackson.databind.node.ObjectNode) whole)
              .put("startMs", 0)
              .put("endMs", duration);
          accepted.add(whole);
        }
      }
    object.set("matches", accepted);
    object.put("serverScopeValidated", true);
    return result;
  }
}
