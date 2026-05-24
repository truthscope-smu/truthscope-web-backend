package com.truthscope.web.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "truthscope.gemini.circuit-breaker")
public record GeminiCircuitBreakerPolicy(
    int failureRateThreshold,
    Duration waitDurationInOpenState,
    int slidingWindowSize,
    Duration slowCallDurationThreshold,
    int slowCallRateThreshold,
    int minimumNumberOfCalls) {
  // Resilience4j config 와 별도 — 향후 운영 가시화 또는 SLO 표시 용
}
