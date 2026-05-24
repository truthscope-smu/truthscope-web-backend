package com.truthscope.web.config;

import com.truthscope.web.scoring.Tier3ReasonPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Scoring policy bean 등록 (Wave 1 µ1.2 mini-task — Tier3ReasonPolicy 만 선행).
 *
 * <p>Wave 2 µ2.1 에서 ArticleScorePolicy / ScoreBandPolicy / CascadePolicy / UrlValidatorPolicy /
 * PolicyEvidenceScorer / StanceRatioScorer 6 bean 추가 박제 의무 (PLAN §1 결정 #21 정합).
 *
 * <p>본 Wave 1 mini-task 는 HeuristicValidator constructor injection NoSuchBeanDefinitionException
 * 차단을 위한 Tier3ReasonPolicy @Bean 등록만.
 */
@Configuration
public class ScoringPolicyConfig {

  @Bean
  public Tier3ReasonPolicy tier3ReasonPolicy(
      @Value(
              "${truthscope.tier3-reason.time-keywords-resource:classpath:policies/tier3-reason-time-keywords-v1.txt}")
          Resource timeKeywordsResource,
      @Value("${truthscope.tier3-reason.missing-ref-date-threshold-days:30}")
          int missingRefDateThresholdDays)
      throws IOException {
    Set<String> timeKeywords;
    try (InputStream is = timeKeywordsResource.getInputStream()) {
      timeKeywords =
          new String(is.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .map(String::trim)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .collect(Collectors.toUnmodifiableSet());
    }
    // outOfScopePatterns 초기값 = 빈 Set (Wave 2 또는 별 phase 에서 ADR-018 제외 기준 패턴 채움)
    Set<String> outOfScopePatterns = Set.of();
    return new Tier3ReasonPolicy(timeKeywords, outOfScopePatterns, missingRefDateThresholdDays);
  }
}
