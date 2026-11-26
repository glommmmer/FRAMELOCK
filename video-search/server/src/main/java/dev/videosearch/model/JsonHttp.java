package dev.videosearch.model;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Shared transport; retries stay at the persistent task/budget layer. */
public final class JsonHttp {
  private static final OkHttpClient CLIENT =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(75, TimeUnit.SECONDS)
          .callTimeout(80, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .build();

  public record Reply(int statusCode, String body) {}

  public static Reply call(String method, String url, Map<String, String> headers, byte[] body)
      throws IOException, InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    Request.Builder request = new Request.Builder().url(url);
    headers.forEach(request::header);
    request.method(
        method,
        body == null
            ? null
            : RequestBody.create(body, MediaType.get("application/json; charset=utf-8")));
    Call call = CLIENT.newCall(request.build());
    // Cancelling the task interrupts its thread; close the in-flight socket as well.
    Thread caller = Thread.currentThread();
    java.util.concurrent.ScheduledFuture<?> watch =
        WATCH.scheduleWithFixedDelay(
            () -> {
              if (caller.isInterrupted()) call.cancel();
            },
            1,
            1,
            TimeUnit.SECONDS);
    try (Response response = call.execute()) {
      return new Reply(response.code(), response.body() == null ? "" : response.body().string());
    } finally {
      watch.cancel(false);
    }
  }

  private static final java.util.concurrent.ScheduledExecutorService WATCH =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("http-cancellation-", 0).factory());

  private JsonHttp() {}
}
