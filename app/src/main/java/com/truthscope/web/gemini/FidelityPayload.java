package com.truthscope.web.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Gemini fidelity 분류 structured output JSON schema 정합 DTO.
 *
 * <p>{@code candidates[0].content.parts[0].text} 내부 JSON 배열을 deserialize 하는 대상. stance 값 =
 * "supports" / "refutes" / "neutral" (소문자, Gemini 출력 기준). FidelityClassifierService 에서
 * SUPPORTED/CONTRADICTED/NEUTRAL 로 대문자 변환.
 *
 * @param items 분류된 후보 항목 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FidelityPayload(List<FidelityItem> items) {

  /**
   * Gemini 가 분류한 단일 후보 항목.
   *
   * @param url 원문 URL
   * @param publisher 발행처 (MinisterCode 등)
   * @param title 제목
   * @param stance 충실성 판정 — "supports" / "refutes" / "neutral"
   * @param matchedFields 일치 항목 맵 — 수치/일자/대상/금액/제도명 중 매칭된 키-값 쌍
   * @param summary 1줄 요약 (drop 대상 — EvidenceSnapshot 5필드 불변)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FidelityItem(
      @JsonProperty("url") String url,
      @JsonProperty("publisher") String publisher,
      @JsonProperty("title") String title,
      @JsonProperty("stance") String stance,
      @JsonProperty("matched_fields") Map<String, String> matchedFields,
      @JsonProperty("summary") String summary) {}
}
