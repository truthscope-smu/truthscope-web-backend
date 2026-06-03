package com.truthscope.web.gemini;

import com.truthscope.web.entity.enums.DecisionSource;
import com.truthscope.web.scoring.ClaimDraft;
import java.util.List;

/**
 * GeminiClient 내부 정규화 응답 DTO — cascade 파이프라인 입력 계약.
 *
 * <p>Gemini API 응답 variant (SAFETY 차단 / empty candidates / 2단계 파싱 실패 / CircuitBreaker 개입 / BYOK
 * AUTH 실패)를 단일 인터페이스로 추상화. static factory 3종으로만 생성.
 *
 * <p>BE #74 amend: {@code authFailure} 필드 + {@link #authFailed()} factory 추가. BYOK 호출 시 HTTP
 * 401/403 발생을 별 신호로 분기 — ClaimAnalysisService가 fallback 트리거에 사용. 정상 SAFETY/empty/parse failure는
 * BYOK 성공으로 분류 (재호출 X).
 *
 * @param claims 추출된 {@link ClaimDraft} 목록 — 결과 없으면 빈 목록
 * @param decisionSource 결정 source 분류
 * @param authFailure BYOK 호출 시 인증 실패 (HTTP 401/403) 신호. 기본 false.
 */
public record GeminiResponse(
    List<ClaimDraft> claims, DecisionSource decisionSource, boolean authFailure) {

  public GeminiResponse {
    claims = (claims == null) ? List.of() : List.copyOf(claims);
  }

  /**
   * 응답 불충분 (차단 / 파싱 실패 / CircuitBreaker 개입) 시 생성. authFailure = false.
   *
   * @param source 결정 source
   * @return claims = 빈 목록 인스턴스
   */
  public static GeminiResponse insufficient(DecisionSource source) {
    return new GeminiResponse(List.of(), source, false);
  }

  /**
   * BYOK 호출 시 HTTP 401/403 인증 실패 전용. ClaimAnalysisService가 서버 기본 키로 fallback 트리거.
   *
   * @return claims = 빈 목록, decisionSource = GEMINI, authFailure = true
   */
  public static GeminiResponse authFailed() {
    return new GeminiResponse(List.of(), DecisionSource.GEMINI, true);
  }

  /**
   * Gemini 정상 응답 + 2단계 파싱 성공 시 생성. authFailure = false.
   *
   * @param payload 2단계 파싱 완료 payload
   * @param wrapper Gemini API 응답 wrapper (현재 미사용 — 향후 usageMetadata 로깅 확장용)
   * @return claims = payload claims, decisionSource = GEMINI
   */
  public static GeminiResponse from(
      ClaimAnalysisPayload payload, GeminiGenerateContentResponse wrapper) {
    return new GeminiResponse(payload.toClaimDrafts(), DecisionSource.GEMINI, false);
  }
}
