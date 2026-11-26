package dev.videosearch.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@Import(org.redisson.spring.starter.RedissonAutoConfigurationV2.class)
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisConfiguration {
  @Bean
  org.redisson.spring.starter.RedissonAutoConfigurationCustomizer redissonSettings(
      @Value("${VS_REDIS_HOST:localhost}") String host,
      @Value("${VS_REDIS_PORT:6380}") int port,
      @Value("${VS_REDIS_PASSWORD:}") String password) {
    return config -> {
      var server =
          config
              .useSingleServer()
              .setAddress("redis://" + host + ":" + port)
              .setConnectionMinimumIdleSize(1)
              .setConnectionPoolSize(4)
              .setSubscriptionConnectionMinimumIdleSize(1)
              .setSubscriptionConnectionPoolSize(2);
      if (!password.isBlank()) server.setPassword(password);
    };
  }
}
