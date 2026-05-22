package com.truthscope.web.scoring;

import java.util.Objects;
import java.util.UUID;

/**
 * 집계 4함수의 입력 계약. claim별 3-Tier Cascade 검증 결과를 담는 계산 계층 DTO다. 이 record를 생산하는 구현체(claim score 산출)는
 * Phase 55 범위 밖 후속 트랙 소관이다.
 *
 * <p>계약 불변식(compact constructor가 강제):
 *
 * <ul>
 *   <li>status가 SCORABLE이면 score는 0..100 non-null이다.
 *   <li>status가 비판정 3종(INSUFFICIENT/TIME_SENSITIVE/OUT_OF_SCOPE)이면 score는 null이다.
 * </ul>
 *
 * <p>tier는 표시·coverage 신호용이며 기사 점수 가중치로 쓰지 않는다. sourceTransparency는 점수에 합산하지 않고 D14 집계에만 쓴다.
 *
 * <p>score는 Integer다. 이 record는 계산 계층 DTO이므로 엔티티 VerificationResult.score(Short)와의 타입 매핑은 경계 서비스
 * 책임이다(DECOMPOSITION-ANALYSIS.md 3절). execute 시 Short로 바꾸지 말 것.
 *
 * @param score 0..100, 비판정 claim이면 null
 */
public record ClaimVerificationSignal(
    UUID claimId,
    Short tier,
    Integer score,
    ClaimScoreStatus status,
    SourceTransparency sourceTransparency) {

  public ClaimVerificationSignal {
    Objects.requireNonNull(claimId, "claimId는 null일 수 없다");
    Objects.requireNonNull(tier, "tier는 null일 수 없다");
    Objects.requireNonNull(status, "status는 null일 수 없다");
    Objects.requireNonNull(sourceTransparency, "sourceTransparency는 null일 수 없다");
    if (status == ClaimScoreStatus.SCORABLE) {
      if (score == null || score < 0 || score > 100) {
        throw new IllegalArgumentException("SCORABLE claim은 0..100 score가 필수다: score=" + score);
      }
    } else if (score != null) {
      throw new IllegalArgumentException(
          "비판정 claim은 score가 null이어야 한다: status=" + status + ", score=" + score);
    }
  }
}
