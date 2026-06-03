package com.truthscope.web.scoring;

/**
 * claim 사실 검증 상태 표시 라벨(DISCUSS D12 진실성 5종). claim score 0..100 밴딩에서 도출된다. 비판정 3종(ClaimScoreStatus)은
 * 이 5종 밖 별도 상태다.
 */
public enum TruthLabel {
  FACT("사실"),
  MOSTLY_FACT("대체로 사실"),
  PARTLY_FACT("일부 사실"),
  MOSTLY_NOT_FACT("대체로 사실 아님"),
  NOT_FACT("사실 아님");

  private final String displayName;

  TruthLabel(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
