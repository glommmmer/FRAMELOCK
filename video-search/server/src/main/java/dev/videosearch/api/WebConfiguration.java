package dev.videosearch.api;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  @Bean
  ThreadPoolTaskExecutor mediaResponses() {
    var pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(2);
    pool.setMaxPoolSize(4);
    pool.setQueueCapacity(8);
    pool.setThreadNamePrefix("media-response-");
    pool.setWaitForTasksToCompleteOnShutdown(false);
    return pool;
  }

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer config) {
    config.setTaskExecutor(mediaResponses()).setDefaultTimeout(120000);
  }
}
