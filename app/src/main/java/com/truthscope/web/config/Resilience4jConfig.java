package com.truthscope.web.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j Spring Boot 3 auto-config 활용 — application.yml {@code
 * resilience4j.circuitbreaker.instances.gemini.*} 가 모든 설정 박제 단계로 본 클래스는 marker 만. Spring AOP
 * starter + resilience4j-spring-boot3 의존이 GeminiClient @CircuitBreaker 를 자동 활성.
 *
 * <p>PLAN §1 결정 #15 + #16 + #28 정합. minimum-number-of-calls=3 (rev.3 R2-8/CX-2), record-exceptions
 * + ignore-exceptions 4xx/5xx 분기 (rev.5 CX4-2).
 */
@Configuration
@EnableConfigurationProperties(GeminiCircuitBreakerPolicy.class)
public class Resilience4jConfig {}
