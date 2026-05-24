package com.truthscope.web.gemini;

import java.util.List;

/**
 * Gemini API {@code generateContent} 요청 DTO.
 *
 * <p>Gemini REST API 스펙 정합 — contents + generationConfig 구성. {@link ResponseFormat#mimeType} 은
 * {@code "application/json"} 고정, {@link ResponseFormat#schema} 에 JSON schema 문자열을 전달.
 *
 * @param contents 요청 메시지 목록
 * @param generationConfig 생성 설정 (responseFormat + temperature)
 */
public record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

  /** 단일 메시지 콘텐츠. */
  public record Content(List<Part> parts) {}

  /** 단일 텍스트 part. */
  public record Part(String text) {}

  /**
   * 생성 설정.
   *
   * @param responseFormat structured output 포맷 설정
   * @param temperature 생성 온도 (0.0 ~ 2.0)
   */
  public record GenerationConfig(ResponseFormat responseFormat, double temperature) {}

  /**
   * structured output 포맷.
   *
   * @param mimeType MIME 타입 — {@code "application/json"} 고정
   * @param schema JSON schema 문자열
   */
  public record ResponseFormat(String mimeType, String schema) {}
}
