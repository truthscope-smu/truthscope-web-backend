package com.truthscope.web.scoring;

/**
 * claim이 점수 집계에 쓸 수 있는 상태인지 분류한다.
 *
 * <p>SCORABLE은 0..100 score를 가진 claim(Verdict의 SUPPORTED 또는 CONTRADICTED 대응). 나머지 3종은 비판정으로 score가
 * null이며 기사 종합 점수 집계에서 제외된다(DISCUSS D12).
 *
 * <p>ClaimScoreStatus를 Verdict enum에서 파생할지 별도 유지할지는 ADR-014 소관이다. 본 계약은 status 4값만 고정한다.
 */
public enum ClaimScoreStatus {
  SCORABLE,
  INSUFFICIENT,
  TIME_SENSITIVE,
  OUT_OF_SCOPE
}
