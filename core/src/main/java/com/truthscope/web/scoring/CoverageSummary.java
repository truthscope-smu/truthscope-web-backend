package com.truthscope.web.scoring;

/**
 * 기사 검증 커버리지 집계 결과. 검증 가능 claim 수, 제외 claim 수와 사유별 분리 count, tier 분포를 담는다(DISCUSS D10 집계 최소 계약 +
 * D12 비판정 분리).
 *
 * <p>excludedCount = insufficientCount + timeSensitiveCount + outOfScopeCount.
 */
public record CoverageSummary(
    int scorableCount,
    int excludedCount,
    int insufficientCount,
    int timeSensitiveCount,
    int outOfScopeCount,
    int tier1Count,
    int tier2Count,
    int tier3Count) {

  public CoverageSummary {
    if (scorableCount < 0
        || excludedCount < 0
        || insufficientCount < 0
        || timeSensitiveCount < 0
        || outOfScopeCount < 0
        || tier1Count < 0
        || tier2Count < 0
        || tier3Count < 0) {
      throw new IllegalArgumentException("CoverageSummary의 모든 count는 0 이상이어야 한다");
    }
    if (excludedCount != insufficientCount + timeSensitiveCount + outOfScopeCount) {
      throw new IllegalArgumentException(
          "excludedCount는 사유별 count의 합이어야 한다: excluded=" + excludedCount);
    }
  }
}
