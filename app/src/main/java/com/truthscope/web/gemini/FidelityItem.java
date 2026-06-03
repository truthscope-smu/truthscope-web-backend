package com.truthscope.web.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Gemini fidelity 분류 단일 후보 항목.
 *
 * <p>FidelityClassifierService 가 {@code TypeReference<List<FidelityItem>>} 로 직접 역직렬화한다. stance 값 =
 * "supports" / "refutes" / "neutral" (소문자, Gemini 출력 기준).
 *
 * @param url 원문 URL
 * @param publisher 발행처 (MinisterCode 등)
 * @param title 제목
 * @param stance 충실성 판정 — "supports" / "refutes" / "neutral"
 * @param matchedFields 일치 항목 맵 — 수치/일자/대상/금액/제도명 중 매칭된 키-값 쌍
 * @param summary 1줄 요약 (drop 대상 — EvidenceSnapshot 필드 불변)
 * @param mismatchedFields 불일치 항목 맵 — 비교가 이루어졌으나 불일치 확인된 키-값 쌍
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FidelityItem(
    @JsonProperty("url") String url,
    @JsonProperty("publisher") String publisher,
    @JsonProperty("title") String title,
    @JsonProperty("stance") String stance,
    @JsonProperty("matched_fields") Map<String, String> matchedFields,
    @JsonProperty("summary") String summary,
    @JsonProperty("mismatched_fields") Map<String, String> mismatchedFields) {}
