package dev.videosearch.infra;

import dev.videosearch.api.ApiError;
import java.util.List;
import org.redisson.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RequestLimits {
  private final ObjectProvider<RedissonClient> redis;
  private final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>> local =
      new java.util.concurrent.ConcurrentHashMap<>();

  public RequestLimits(ObjectProvider<RedissonClient> redis) {
    this.redis = redis;
  }

  public void search(String owner) {
    var client = redis.getIfAvailable();
    if (client == null) {
      long now = System.currentTimeMillis();
      var queue = local.computeIfAbsent(owner, x -> new java.util.ArrayDeque<>());
      synchronized (queue) {
        while (!queue.isEmpty() && queue.peekFirst() < now - 60000) queue.removeFirst();
        if (queue.size() >= 10)
          throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "每分钟最多发起10次检索");
        queue.addLast(now);
      }
      return;
    }
    for (String scope : List.of("user:" + owner, "global")) {
      RRateLimiter limiter = client.getRateLimiter("framelock:limit:ai:" + scope);
      limiter.trySetRate(
          RateType.OVERALL, scope.equals("global") ? 30 : 10, 60, RateIntervalUnit.SECONDS);
      if (!limiter.tryAcquire())
        throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求较多，请稍后再试");
    }
  }
}
