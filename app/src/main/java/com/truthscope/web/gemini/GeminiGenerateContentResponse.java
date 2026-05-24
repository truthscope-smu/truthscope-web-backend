package com.truthscope.web.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Gemini {@code generateContent} API 응답 wrapper DTO.
 *
 * <p>공식 문서 {@code ai.google.dev/api/generate-content} 응답 스펙 정합. 알 수 없는 필드는
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 로 무시하여 API 버전 업 시 역호환성 유지.
 *
 * @param candidates 생성 결과 후보 목록
 * @param promptFeedback 프롬프트 피드백 (blockReason 포함)
 * @param usageMetadata 토큰 사용량 메타데이터
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateContentResponse(
    List<Candidate> candidates, PromptFeedback promptFeedback, UsageMetadata usageMetadata) {

  /** 생성 결과 단일 후보. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Candidate(Content content, String finishReason, int index) {}

  /** 단일 후보의 콘텐츠. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Content(List<Part> parts, String role) {}

  /** 단일 텍스트 part — structured output JSON 이 이 안에 포함됨. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Part(String text) {}

  /**
   * 프롬프트 피드백.
   *
   * <p>{@code blockReason} 이 non-null 이면 응답이 안전 정책으로 차단된 것.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PromptFeedback(String blockReason, List<SafetyRating> safetyRatings) {}

  /** 안전 등급 단일 항목. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SafetyRating(String category, String probability) {}

  /** 토큰 사용량 메타데이터. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UsageMetadata(
      int promptTokenCount, int candidatesTokenCount, int totalTokenCount) {}
}
