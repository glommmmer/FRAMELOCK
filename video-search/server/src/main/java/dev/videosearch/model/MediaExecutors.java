package dev.videosearch.model;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MediaExecutors {
  @Bean("ocrExecutor")
  public ThreadPoolTaskExecutor ocr() {
    return pool("ocr-");
  }

  @Bean("asrExecutor")
  public ThreadPoolTaskExecutor asr() {
    return pool("asr-");
  }

  private ThreadPoolTaskExecutor pool(String name) {
    var pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setMaxPoolSize(1);
    pool.setQueueCapacity(4);
    pool.setThreadNamePrefix(name);
    pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    return pool;
  }
}
