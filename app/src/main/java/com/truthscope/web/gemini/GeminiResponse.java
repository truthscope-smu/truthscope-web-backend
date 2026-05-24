package com.truthscope.web.gemini;

import com.truthscope.web.entity.enums.DecisionSource;
import com.truthscope.web.scoring.ClaimDraft;
import java.util.List;

/**
 * GeminiClient 내부 정규화 응답 DTO — cascade 파이프라인 입력 계약.
 *
 * <p>Gemini API 응답 variant (SAFETY 차단 / empty candidates / 2단계 파싱 실패 / CircuitBreaker 개입)를 단일
 * 인터페이스로 추상화. static factory 2종으로만 생성 (rev.5 amend Round 4 CX4-1).
 *
 * @param claims 추출된 {@link ClaimDraft} 목록 — 결과 없으면 빈 목록
 * @param decisionSource 결정 source 분류
 */
public record GeminiResponse(List<ClaimDraft> claims, DecisionSource decisionSource) {

  /**
   * 응답 불충분 (차단 / 파싱 실패 / CircuitBreaker 개입) 시 생성.
   *
   * @param source 결정 source
   * @return claims = 빈 목록 인스턴스
   */
  public static GeminiResponse insufficient(DecisionSource source) {
    return new GeminiResponse(List.of(), source);
  }

  /**
   * Gemini 정상 응답 + 2단계 파싱 성공 시 생성.
   *
   * <p>{@link ClaimAnalysisPayload#toClaimDrafts()} 로 변환 후 {@link DecisionSource#GEMINI} 박제.
   *
   * @param payload 2단계 파싱 완료 payload
   * @param wrapper Gemini API 응답 wrapper (현재 미사용 — 향후 usageMetadata 로깅 확장용)
   * @return claims = payload claims, decisionSource = GEMINI
   */
  public static GeminiResponse from(
      ClaimAnalysisPayload payload, GeminiGenerateContentResponse wrapper) {
    return new GeminiResponse(payload.toClaimDrafts(), DecisionSource.GEMINI);
  }
}
