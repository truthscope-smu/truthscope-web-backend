package com.truthscope.web.scoring;

import java.util.List;
import java.util.Optional;

/**
 * Tier 2c — 정책 field 5종 (수치/일자/대상/금액/제도명) 일치율 × 100 + critical-field 조건부 cap 50.
 *
 * <p>v2: critical field 불일치 감지 시만 cap=criticalFieldCapPercent 적용(ADR-021 조건부 cap). source 가
 * sourceCountThreshold 미만이면 Optional.empty(), 이상이면 matchedFields 의 평균 일치율을 0..100 로 환산. mismatch
 * 없으면 ratio 그대로 반환(최대 100). mismatch 있으면 criticalFieldCapPercent(50) 상한.
 *
 * <p>stance 정합: CONTRADICTED(반박) 출처의 matchedFields 는 claim 을 뒷받침하지 않으므로 양성 일치율에 합산하지 않는다.
 * refuting-evidence retention 정합 — 반박 출처는 필터에서 보존되지만 score 는 0 쪽으로 강하하여 truthLabel 이 NOT_FACT 로
 * 도출되게 한다. 분모(totalFields × 전체 source 수)는 그대로 두어 반박 출처가 점수를 희석한다.
 */
public class PolicyEvidenceScorer implements ClaimScoreCalculator {

  private static final String CONTRADICTED = "CONTRADICTED";

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
      // 반박 출처의 일치 필드는 claim 뒷받침이 아니므로 양성 점수에 합산하지 않는다 (stance 정합).
      if (CONTRADICTED.equals(src.stance())) {
        continue;
      }
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
