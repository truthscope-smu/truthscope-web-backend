package com.truthscope.web.scoring;

import java.util.Objects;
import java.util.Set;

/**
 * Tier 3 reason 휴리스틱 판정 policy. 외부 (app 모듈) 가 ClassPathResource 로 키워드 로드 후 생성자 주입. core 모듈 Spring
 * 의존 0 제약 정합 — plain Java collection 만 보관.
 */
public record Tier3ReasonPolicy(
    Set<String> timeKeywords, Set<String> outOfScopePatterns, int missingRefDateThresholdDays) {

  public Tier3ReasonPolicy {
    Objects.requireNonNull(timeKeywords, "timeKeywords는 null 일 수 없다");
    Objects.requireNonNull(outOfScopePatterns, "outOfScopePatterns는 null 일 수 없다");
    if (missingRefDateThresholdDays < 0) {
      throw new IllegalArgumentException(
          "missingRefDateThresholdDays는 0 이상이어야 한다: " + missingRefDateThresholdDays);
    }
  }
}
