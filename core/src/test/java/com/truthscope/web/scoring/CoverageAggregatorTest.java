package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageAggregatorTest {

  private static ClaimVerificationSignal scorable(short tier) {
    return new ClaimVerificationSignal(
        UUID.randomUUID(), tier, 80, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT);
  }

  private static ClaimVerificationSignal excluded(ClaimScoreStatus status, short tier) {
    return new ClaimVerificationSignal(
        UUID.randomUUID(), tier, null, status, SourceTransparency.NONE);
  }

  // 케이스 1: 빈 리스트 → 전부 0
  @Test
  void 빈_리스트이면_전부_0() {
    CoverageSummary result = CoverageAggregator.aggregateCoverage(List.of());
    assertThat(result.scorableCount()).isEqualTo(0);
    assertThat(result.excludedCount()).isEqualTo(0);
    assertThat(result.insufficientCount()).isEqualTo(0);
    assertThat(result.timeSensitiveCount()).isEqualTo(0);
    assertThat(result.outOfScopeCount()).isEqualTo(0);
    assertThat(result.tier1Count()).isEqualTo(0);
    assertThat(result.tier2Count()).isEqualTo(0);
    assertThat(result.tier3Count()).isEqualTo(0);
  }

  // 케이스 2: SCORABLE 2(tier 1,2) + INSUFFICIENT 1(tier 3) + TIME_SENSITIVE 1(tier 3) + OUT_OF_SCOPE
  // 1(tier 3)
  // → scorable 2, excluded 3, insufficient 1, timeSensitive 1, outOfScope 1, tier1 1, tier2 1,
  // tier3 3
  // excludedCount(3) = insufficientCount(1) + timeSensitiveCount(1) + outOfScopeCount(1) 명시 단언
  @Test
  void 혼합_5개_케이스2() {
    List<ClaimVerificationSignal> claims =
        List.of(
            scorable((short) 1),
            scorable((short) 2),
            excluded(ClaimScoreStatus.INSUFFICIENT, (short) 3),
            excluded(ClaimScoreStatus.TIME_SENSITIVE, (short) 3),
            excluded(ClaimScoreStatus.OUT_OF_SCOPE, (short) 3));
    CoverageSummary result = CoverageAggregator.aggregateCoverage(claims);

    assertThat(result.scorableCount()).isEqualTo(2);
    assertThat(result.excludedCount()).isEqualTo(3);
    assertThat(result.insufficientCount()).isEqualTo(1);
    assertThat(result.timeSensitiveCount()).isEqualTo(1);
    assertThat(result.outOfScopeCount()).isEqualTo(1);
    assertThat(result.tier1Count()).isEqualTo(1);
    assertThat(result.tier2Count()).isEqualTo(1);
    assertThat(result.tier3Count()).isEqualTo(3);

    // excludedCount가 사유별 count의 합(1+1+1=3)임을 명시 단언 (pass-only 금지 원칙)
    assertThat(result.excludedCount())
        .isEqualTo(
            result.insufficientCount() + result.timeSensitiveCount() + result.outOfScopeCount());
    assertThat(result.insufficientCount() + result.timeSensitiveCount() + result.outOfScopeCount())
        .isEqualTo(3);
  }

  // 케이스 3: 단일 SCORABLE claim(tier 1) → scorable 1, excluded 0, tier1 1
  @Test
  void 단일_SCORABLE_tier1() {
    List<ClaimVerificationSignal> claims = List.of(scorable((short) 1));
    CoverageSummary result = CoverageAggregator.aggregateCoverage(claims);
    assertThat(result.scorableCount()).isEqualTo(1);
    assertThat(result.excludedCount()).isEqualTo(0);
    assertThat(result.tier1Count()).isEqualTo(1);
    assertThat(result.tier2Count()).isEqualTo(0);
    assertThat(result.tier3Count()).isEqualTo(0);
  }

  // 케이스 4: claims null → NPE
  @Test
  void claims_null이면_NullPointerException() {
    assertThatNullPointerException().isThrownBy(() -> CoverageAggregator.aggregateCoverage(null));
  }
}
