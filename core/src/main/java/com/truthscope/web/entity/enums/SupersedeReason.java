package com.truthscope.web.entity.enums;

/**
 * 재검증 supersede 사유 6종 (ADR-009 rev.3 — V9 CHECK 대문자 정합).
 *
 * <p>4조건 판별(SupersedeDecider) 산출 4종 + 트리거 출처 표기 2종(USER_REPORT, SCHEDULED_REVERIFY — UC-145/146 트리거
 * 기록용, v1.x에서는 SCHEDULED_REVERIFY 미사용).
 */
public enum SupersedeReason {
  LABEL_CHANGED,
  SCORE_DRIFT,
  URL_REPLACEMENT,
  TIER_CHANGED,
  USER_REPORT,
  SCHEDULED_REVERIFY
}
