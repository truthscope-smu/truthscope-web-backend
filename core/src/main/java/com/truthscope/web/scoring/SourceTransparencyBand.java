package com.truthscope.web.scoring;

/** 기사 수준 출처 표기 요약 밴드(DISCUSS D14). 기사 대표값이 아니라 보수적 경고다. */
public enum SourceTransparencyBand {
  ALL_EXPLICIT("모두 명시"),
  SOME_UNCLEAR("일부 불명확"),
  MISSING_SOURCE("출처 누락 있음");

  private final String displayName;

  SourceTransparencyBand(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
