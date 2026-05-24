package com.truthscope.web.scoring;

import java.util.List;
import java.util.Optional;

/**
 * Tier 2b — SUPPORTED 비율 × 100. 충돌 (SUPPORTED 1 + CONTRADICTED 2) → min(30, ratio×100).
 *
 * <p>unrelated/partial 은 denominator 에서 제외. v1.x skeleton 구현.
 */
public class StanceRatioScorer implements ClaimScoreCalculator {

  private static final String SUPPORTED = "SUPPORTED";
  private static final String CONTRADICTED = "CONTRADICTED";

  @Override
  public Optional<Integer> calculate(
      ClaimDraft claim, List<EvidenceSnapshot> sources, CascadePolicy policy) {
    if (sources == null || sources.size() < policy.sourceCountThreshold()) {
      return Optional.empty();
    }
    long supported = sources.stream().filter(s -> SUPPORTED.equals(s.stance())).count();
    long contradicted = sources.stream().filter(s -> CONTRADICTED.equals(s.stance())).count();
    long denominator = supported + contradicted;
    if (denominator == 0) {
      return Optional.empty();
    }
    int ratio = (int) ((supported * 100) / denominator);
    // 충돌 시 cap 30 (SUPPORTED < CONTRADICTED)
    if (contradicted > supported) {
      return Optional.of(Math.min(30, ratio));
    }
    return Optional.of(Math.max(0, Math.min(100, ratio)));
  }
}
