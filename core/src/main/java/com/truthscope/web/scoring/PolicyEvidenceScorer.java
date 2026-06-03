package com.truthscope.web.scoring;

import java.util.List;
import java.util.Optional;

/**
 * Tier 2c — 정책 field 5종 (수치/일자/대상/금액/제도명) 일치율 × 100 + critical-field 조건부 cap 50.
 *
 * <p>v2: critical field 불일치 감지 시만 cap=criticalFieldCapPercent 적용(ADR-021 조건부 cap). source 가
 * sourceCountThreshold 미만이면 Optional.empty(), 이상이면 matchedFields 의 평균 일치율을 0..100 로 환산. mismatch
 * 없으면 ratio 그대로 반환(최대 100). mismatch 있으면 criticalFieldCapPercent(50) 상한.
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
    // v2: critical-field mismatch 감지 시만 cap 적용 (ADR-021 조건부 cap 구현).
    // mismatch 없으면 ratio 그대로 반환(최대 100). mismatch 있으면 criticalFieldCapPercent(50) 상한.
    boolean hasCriticalMismatch =
        sources.stream()
            .anyMatch(
                s ->
                    s.mismatchedFields() != null
                        && policy.claimSplitFields().stream()
                            .anyMatch(f -> s.mismatchedFields().containsKey(f)));
    int capped = hasCriticalMismatch ? Math.min(ratio, policy.criticalFieldCapPercent()) : ratio;
    return Optional.of(Math.max(0, Math.min(100, capped)));
  }
}
