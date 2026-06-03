package com.truthscope.web.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

  /**
   * 분석 비동기 실행 executor. 테스트에서 truthscope.async.enabled=false로 끄고 SyncTaskExecutor를 주입해 @Async를 인라인
   * 실행한다(빈 이름 충돌 없이 결정적 override).
   */
  @Bean("analysisExecutor")
  @ConditionalOnProperty(
      name = "truthscope.async.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Executor analysisExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(2);
    ex.setMaxPoolSize(4);
    ex.setQueueCapacity(25);
    ex.setThreadNamePrefix("analysis-");
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    ex.initialize();
    return ex;
  }
}
