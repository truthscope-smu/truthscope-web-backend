package com.truthscope.web.support;

import java.util.concurrent.Executor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

@TestConfiguration
public class SyncAnalysisExecutorConfig {
  @Bean("analysisExecutor")
  @Primary
  public Executor analysisExecutor() {
    return new SyncTaskExecutor(); // 호출 스레드에서 즉시 실행, @Async 인라인화
  }
}
