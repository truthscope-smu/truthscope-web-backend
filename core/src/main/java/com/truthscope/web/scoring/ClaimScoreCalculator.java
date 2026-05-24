package com.truthscope.web.scoring;

import java.util.List;
import java.util.Optional;

/** claim score 산출 Strategy. PolicyEvidenceScorer (2c 1순위) / StanceRatioScorer (2b fallback). */
public interface ClaimScoreCalculator {
  /**
   * @param claim 대상 ClaimDraft
   * @param sources Tier 2 검증으로 수집된 EvidenceSnapshot 리스트
   * @param policy cascade 정책 (sourceCountThreshold, criticalFieldCapPercent 등)
   * @return 0..100 SCORABLE 인 경우; Optional.empty 이면 INSUFFICIENT 후보
   */
  Optional<Integer> calculate(
      ClaimDraft claim, List<EvidenceSnapshot> sources, CascadePolicy policy);
}
