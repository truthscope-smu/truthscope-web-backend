package com.truthscope.web.scoring;

/**
 * claim 단위 출처 표기 투명성 3단계(DISCUSS D11·D14). EXPLICIT=명시, AMBIGUOUS=모호, NONE=없음. 점수 0..100에 합산하지 않고
 * aggregateSourceTransparency 집계에만 쓴다.
 */
public enum SourceTransparency {
  EXPLICIT,
  AMBIGUOUS,
  NONE
}
