package com.truthscope.web.scoring;

/**
 * Gemini 또는 휴리스틱이 1차로 부여한 status 후보. 최종 status 는 cascade orchestrator 가 lock.
 *
 * <ul>
 *   <li>{@link #SCORABLE} — 검증 가능한 사실 주장, score 산출 대상
 *   <li>{@link #INSUFFICIENT_CANDIDATE} — 근거 부족 후보, Tier 3 처리 대상
 *   <li>{@link #TIME_SENSITIVE_CANDIDATE} — 시점 의존 사실 후보, 재검증 필요
 *   <li>{@link #OUT_OF_SCOPE_CANDIDATE} — 의견·예측 등 검증 범위 밖 후보
 * </ul>
 */
public enum ClaimStatusCandidate {
  SCORABLE,
  INSUFFICIENT_CANDIDATE,
  TIME_SENSITIVE_CANDIDATE,
  OUT_OF_SCOPE_CANDIDATE
}
