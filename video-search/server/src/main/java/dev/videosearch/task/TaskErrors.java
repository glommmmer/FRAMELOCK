package dev.videosearch.task;

/** Expose actionable codes without vendor response bodies, signed URLs or credentials. */
public final class TaskErrors {
  private TaskErrors() {}

  public static String describe(Exception error) {
    String message = error.getMessage() == null ? "" : error.getMessage();
    if (message.contains("BUDGET_STOP")) return "BUDGET_STOP：模型已结算与预留额度到达停止线，未继续调用";
    if (message.contains("not configured")) return "CONFIG_MISSING：请检查模型或 OSS 配置";
    if (message.startsWith("MODEL_HTTP_")
        || message.startsWith("ASR_HTTP_")
        || message.startsWith("QDRANT_HTTP_"))
      return message
          .replaceAll("[^A-Za-z0-9_:.-]", "")
          .substring(0, Math.min(100, message.replaceAll("[^A-Za-z0-9_:.-]", "").length()));
    if (error instanceof java.net.http.HttpTimeoutException
        || error instanceof java.util.concurrent.TimeoutException)
      return "PROVIDER_TIMEOUT：外部服务超时，可重试，未知计费仍保留预留";
    if (error instanceof InterruptedException) return "INTERRUPTED：任务已取消、租约失效或服务正在退出";
    if (message.contains("JSON") || message.contains("Verifier"))
      return "MODEL_RESPONSE_INVALID：模型输出缺字段、格式或时间不合法";
    return "PROCESSING_FAILED：" + error.getClass().getSimpleName();
  }
}
