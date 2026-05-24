package com.truthscope.web.adapter.factcheck;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Google Fact Check Tools API adapter. v1.x = 미라우팅 stub (bean 보존 only).
 *
 * <p>cascade orchestrator 가 Tier 1 (factcheck_cache lookup) 후 Google FC API 호출을 시도하지만 본 phase 는 항상
 * Optional.empty() 반환. v2 트랙(ADR-018 §결정 5)에서 실 호출 활성화.
 */
@Component
public class GoogleFcAdapter {

  /**
   * 주어진 claim text 로 Google FC API 매칭 시도.
   *
   * @return 항상 Optional.empty() — v1.x 미라우팅
   */
  public Optional<FactCheckResult> findMatching(String claimText) {
    return Optional.empty();
  }

  /** v1.x stub 결과 record — 추후 ADR-018 §결정 5 활성화 시 확장. */
  public record FactCheckResult(String publisher, String rating, String reviewUrl) {}
}
