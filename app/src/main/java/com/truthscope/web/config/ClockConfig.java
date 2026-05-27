package com.truthscope.web.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production Clock @Bean 등록 (H4 codex Round 3 amend).
 *
 * <p>WikipediaRevisionChecker는 Clock 필드를 생성자 주입으로 받는다. production 컨텍스트에서 Clock @Bean이 없으면
 * NoSuchBeanDefinitionException 발생. 본 @Configuration이 Clock.systemUTC()를 @Bean으로 등록하여 production
 * 빌드를 보호한다.
 *
 * <p>테스트 컨텍스트에서는 WikipediaAdapterIntegrationTest의 @TestConfiguration ClockTestConfig가
 * Clock.fixed()를 @Bean으로 등록하여 cassette timestamp 24h 경계를 mock한다.
 * spring.main.allow-bean-definition-overriding=true 설정으로 @TestConfiguration이 우선.
 *
 * <p>H4 codex Round 3 amend: 미결 사항에서 변경 파일 표로 승격 — production 빌드 보호 의무. Atomic commit T1에 포함 (Wave
 * 1 — 인프라 @Bean 신규).
 */
@Configuration
public class ClockConfig {

  /**
   * UTC 기준 시스템 시계 @Bean.
   *
   * <p>WikipediaRevisionChecker 생성자가 {@code Clock clock} 파라미터를 필수로 받으므로 본 @Bean이 없으면 Spring 컨텍스트
   * 초기화 실패. cassette-runbook.md "잔여 quirk: Clock.fixed() 주입 의무" 절차 정합.
   *
   * @return Clock.systemUTC() — UTC 기준 시스템 시계
   */
  @Bean
  public Clock systemClock() {
    return Clock.systemUTC();
  }
}
