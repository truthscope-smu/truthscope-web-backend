package com.truthscope.web.scoring;

/** 기사 수준 출처 표기 투명성 집계 결과(DISCUSS D14). 분포(명시/모호/없음 개수)가 1차 표시, band는 보조 경고 표시. */
public record SourceTransparencySummary(
    int explicitCount, int ambiguousCount, int noneCount, SourceTransparencyBand band) {}
