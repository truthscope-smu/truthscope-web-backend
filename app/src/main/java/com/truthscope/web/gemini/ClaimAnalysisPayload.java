package com.truthscope.web.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import java.util.List;
import java.util.UUID;

/**
 * Gemini structured output JSON schema 정합 DTO.
 *
 * <p>{@code candidates[0].content.parts[0].text} 내부 JSON 을 deserialize 하는 대상. 2단계 파싱 전략 정합 (rev.5
 * amend Round 4 CX4-1).
 *
 * @param claims 추출된 claim 항목 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimAnalysisPayload(List<ClaimItem> claims) {

  /**
   * Gemini 가 추출한 단일 claim 항목.
   *
   * @param claimText claim 텍스트
   * @param speakerName 발언자 이름 (nullable)
   * @param isQuotedClaim 직접 인용 여부
   * @param originalContext 원문 맥락 (nullable)
   * @param claimStatusCandidate Gemini 가 부여한 status 후보
   * @param splitGroup 원 claim 분할 묶음 식별자 UUID 문자열 (nullable)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ClaimItem(
      @JsonProperty("claim_text") String claimText,
      @JsonProperty("speaker_name") String speakerName,
      @JsonProperty("is_quoted_claim") boolean isQuotedClaim,
      @JsonProperty("original_context") String originalContext,
      @JsonProperty("claim_status_candidate") ClaimStatusCandidate claimStatusCandidate,
      @JsonProperty("split_group") String splitGroup) {

    /**
     * {@link ClaimDraft} 로 변환.
     *
     * <p>{@code splitGroup} 이 null 이면 {@link ClaimDraft#splitGroup()} 도 null — 독립 단일 claim 의미.
     */
    public ClaimDraft toClaimDraft() {
      UUID splitGroupUuid = splitGroup == null ? null : UUID.fromString(splitGroup);
      return new ClaimDraft(
          UUID.randomUUID(),
          claimText,
          speakerName,
          isQuotedClaim,
          originalContext,
          claimStatusCandidate,
          splitGroupUuid);
    }
  }

  /**
   * 모든 {@link ClaimItem} 을 {@link ClaimDraft} 목록으로 변환.
   *
   * <p>{@code claims} 가 null 이면 빈 목록 반환.
   */
  public List<ClaimDraft> toClaimDrafts() {
    return claims == null ? List.of() : claims.stream().map(ClaimItem::toClaimDraft).toList();
  }
}
