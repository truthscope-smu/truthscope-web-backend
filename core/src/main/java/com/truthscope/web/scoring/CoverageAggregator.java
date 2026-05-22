package com.truthscope.web.scoring;

import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 기사 검증 커버리지를 집계하는 순수 함수(DISCUSS D10 집계 최소 계약 + D12 비판정 분리).
 *
 * <p>검증 가능(SCORABLE) claim 수, 제외 claim 수와 사유별(INSUFFICIENT/TIME_SENSITIVE/OUT_OF_SCOPE) 분리 count,
 * tier 분포(tier 값 1/2/3)를 만든다. tier가 1/2/3이 아니면 어느 tier count에도 포함하지 않는다(tier는 1/2/3을 기대).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoverageAggregator {

  public static CoverageSummary aggregateCoverage(List<ClaimVerificationSignal> claims) {
    Objects.requireNonNull(claims, "claims는 null일 수 없다");
    int scorable = 0;
    int insufficient = 0;
    int timeSensitive = 0;
    int outOfScope = 0;
    int tier1 = 0;
    int tier2 = 0;
    int tier3 = 0;
    for (ClaimVerificationSignal c : claims) {
      switch (c.status()) {
        case SCORABLE -> scorable++;
        case INSUFFICIENT -> insufficient++;
        case TIME_SENSITIVE -> timeSensitive++;
        case OUT_OF_SCOPE -> outOfScope++;
      }
      short tier = c.tier();
      if (tier == 1) {
        tier1++;
      } else if (tier == 2) {
        tier2++;
      } else if (tier == 3) {
        tier3++;
      }
    }
    int excluded = insufficient + timeSensitive + outOfScope;
    return new CoverageSummary(
        scorable, excluded, insufficient, timeSensitive, outOfScope, tier1, tier2, tier3);
  }
}
