package com.truthscope.web.gemini;

import java.util.List;

/**
 * Gemini API {@code generateContent} 요청 DTO.
 *
 * <p>Gemini REST API 스펙 정합 — contents + generationConfig 구성. {@link
 * GenerationConfig#responseMimeType} 은 {@code "application/json"} 고정 (structured output).
 * responseSchema enforcement 는 v2 트랙.
 *
 * @param contents 요청 메시지 목록
 * @param generationConfig 생성 설정 (responseMimeType + temperature)
 */
public record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

  /** 단일 메시지 콘텐츠. */
  public record Content(List<Part> parts) {}

  /** 단일 텍스트 part. */
  public record Part(String text) {}

  /**
   * 생성 설정 — Gemini {@code generationConfig} 스펙 정합.
   *
   * <p>OpenAI 식 {@code responseFormat} 중첩이 아니라 Gemini 스펙의 {@code responseMimeType} 평면 필드를 사용한다
   * (Gemini 는 {@code response_format} 를 모르는 필드로 400 INVALID_ARGUMENT 반환).
   *
   * @param responseMimeType 응답 MIME 타입 — {@code "application/json"} 고정 (structured output)
   * @param temperature 생성 온도 (0.0 ~ 2.0)
   */
  public record GenerationConfig(String responseMimeType, double temperature) {}
}
