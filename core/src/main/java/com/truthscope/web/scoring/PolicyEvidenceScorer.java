package com.truthscope.web.scoring;

import java.util.List;
import java.util.Optional;

/**
 * Tier 2c — 정책 field 5종 (수치/일자/대상/금액/제도명) 일치율 × 100 + critical-field cap 50 + claim split 정합.
 *
 * <p>v1.x skeleton: source 가 sourceCountThreshold 미만이면 Optional.empty(), 이상이면 matchedFields 의 평균
 * 일치율을 0..100 로 환산. critical field 1개 불일치 시 cap=criticalFieldCapPercent 적용.
 *
 * <p>실제 정책 field 정규화 + claim split UUID 결합 알고리즘은 v2 트랙 (ADR-021).
 */
public class PolicyEvidenceScorer implements ClaimScoreCalculator {

  @Override
  public Optional<Integer> calculate(
      ClaimDraft claim, List<EvidenceSnapshot> sources, CascadePolicy policy) {
    if (sources == null || sources.size() < policy.sourceCountThreshold()) {
      return Optional.empty();
    }
    // v1.x skeleton: matchedFields 평균 일치율로 score 산출
    int totalFields = policy.claimSplitFields().size();
    if (totalFields == 0) {
      return Optional.of(50); // 안전 default
    }
    int matchedCount = 0;
    for (EvidenceSnapshot src : sources) {
      if (src.matchedFields() != null) {
        matchedCount += src.matchedFields().size();
      }
    }
    int ratio = Math.min(100, (matchedCount * 100) / Math.max(1, totalFields * sources.size()));
    // v1.x skeleton: 모든 ratio에 cap 무조건 적용. v2 트랙(ADR-021)에서 critical-field mismatch detection 후 조건부
    // 적용으로 전환.
    int capped = Math.min(ratio, policy.criticalFieldCapPercent());
    return Optional.of(Math.max(0, Math.min(100, capped)));
  }
}
