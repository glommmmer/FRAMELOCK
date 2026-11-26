package dev.videosearch;

import dev.videosearch.lab.Lab;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    excludeName = {
      "org.redisson.spring.starter.RedissonAutoConfigurationV2",
      "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
    })
public class VideoSearchApplication {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("evaluate")) {
      Lab.evaluateSaved(args);
      return;
    }
    if (args.length > 0 && args[0].equals("lab")) {
      Lab.run(args);
      return;
    }
    SpringApplication.run(VideoSearchApplication.class, args);
  }
}
