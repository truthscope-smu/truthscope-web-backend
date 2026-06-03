package com.truthscope.web.config;

import com.truthscope.web.scoring.ArticleScorePolicy;
import com.truthscope.web.scoring.CascadePolicy;
import com.truthscope.web.scoring.ClaimScoreCalculator;
import com.truthscope.web.scoring.PolicyEvidenceScorer;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.scoring.StanceRatioScorer;
import com.truthscope.web.scoring.Tier3ReasonPolicy;
import com.truthscope.web.scoring.UrlValidatorPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Scoring policy bean 등록.
 *
 * <p>Wave 1 µ1.2: Tier3ReasonPolicy @Bean (HeuristicValidator constructor injection
 * NoSuchBeanDefinitionException 차단).
 *
 * <p>Wave 2 µ2.2: ArticleScorePolicy / ScoreBandPolicy / CascadePolicy / UrlValidatorPolicy /
 * PolicyEvidenceScorer / StanceRatioScorer 6 @Bean 추가 (PLAN §1 결정 #21 정합).
 */
@Configuration
public class ScoringPolicyConfig {

  @Bean
  public Tier3ReasonPolicy tier3ReasonPolicy(
      @Value(
              "${truthscope.tier3-reason.time-keywords-resource:classpath:policies/tier3-reason-time-keywords-v1.txt}")
          Resource timeKeywordsResource,
      @Value(
              "${truthscope.tier3-reason.out-of-scope-patterns-resource:classpath:policies/tier3-reason-out-of-scope-patterns-v1.txt}")
          Resource outOfScopePatternsResource,
      @Value("${truthscope.tier3-reason.missing-ref-date-threshold-days:30}")
          int missingRefDateThresholdDays)
      throws IOException {
    if (missingRefDateThresholdDays < 0) {
      throw new IllegalArgumentException(
          "truthscope.tier3-reason.missing-ref-date-threshold-days 는 0 이상이어야 한다: "
              + missingRefDateThresholdDays);
    }
    Set<String> timeKeywords;
    try (InputStream is = timeKeywordsResource.getInputStream()) {
      timeKeywords = loadPatterns(is);
    }
    Set<String> outOfScopePatterns;
    try (InputStream is = outOfScopePatternsResource.getInputStream()) {
      outOfScopePatterns = loadPatterns(is);
    }
    return new Tier3ReasonPolicy(timeKeywords, outOfScopePatterns, missingRefDateThresholdDays);
  }

  /** 패턴 파일 InputStream 에서 줄 목록을 읽어 Set 으로 반환한다. # 주석 및 빈 줄 무시. */
  private Set<String> loadPatterns(InputStream is) throws IOException {
    return new String(is.readAllBytes(), StandardCharsets.UTF_8)
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Bean
  public ArticleScorePolicy articleScorePolicy(
      @Value("${truthscope.article-score.score-floor:1.0}") double scoreFloor) {
    // scoreFloor 기본값 1.0 — Phase 55 DISCUSS D13 기하평균 붕괴 방지 양수 하한
    // rounding = HALF_UP — production 값 확정 전 합리적 기본값 (UNNECESSARY 금지)
    return new ArticleScorePolicy(scoreFloor, RoundingMode.HALF_UP);
  }

  @Bean
  public ScoreBandPolicy scoreBandPolicy(
      @Value("${truthscope.score-band.fact-min:80}") int factMin,
      @Value("${truthscope.score-band.mostly-fact-min:60}") int mostlyFactMin,
      @Value("${truthscope.score-band.partly-fact-min:40}") int partlyFactMin,
      @Value("${truthscope.score-band.mostly-not-fact-min:20}") int mostlyNotFactMin) {
    // 5개 밴드 내림차순 임계값 — Phase 55 DISCUSS 5장 4절 이연, production 값 확정 전 합리적 기본값
    return new ScoreBandPolicy(factMin, mostlyFactMin, partlyFactMin, mostlyNotFactMin);
  }

  @Bean
  public CascadePolicy cascadePolicy(
      @Value("${truthscope.cascade.source-count-threshold:1}") int sourceCountThreshold,
      @Value("${truthscope.cascade.tier1-hit-required:true}") boolean tier1HitRequired,
      @Value("${truthscope.cascade.critical-field-cap-percent:50}") int criticalFieldCapPercent) {
    return new CascadePolicy(
        sourceCountThreshold,
        tier1HitRequired,
        criticalFieldCapPercent,
        List.of("수치", "일자", "대상", "금액", "제도명"));
  }

  @Bean
  public UrlValidatorPolicy urlValidatorPolicy(
      @Value("${truthscope.url-validator.connect-timeout:PT5S}") Duration connectTimeout,
      @Value("${truthscope.url-validator.read-timeout:PT5S}") Duration readTimeout,
      @Value("${truthscope.url-validator.redirect-max-depth:5}") int redirectMaxDepth,
      @Value("${truthscope.url-validator.retry-count:1}") int retryCount,
      @Value("${truthscope.url-validator.retry-backoff:PT1S}") Duration retryBackoff) {
    return new UrlValidatorPolicy(
        connectTimeout, readTimeout, redirectMaxDepth, retryCount, retryBackoff);
  }

  @Bean
  public ClaimScoreCalculator policyScorer() {
    return new PolicyEvidenceScorer();
  }

  @Bean
  public ClaimScoreCalculator stanceScorer() {
    return new StanceRatioScorer();
  }
}
