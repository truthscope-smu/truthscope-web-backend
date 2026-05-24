package com.truthscope.web.scoring;

import java.util.Objects;
import java.util.UUID;

/**
 * Claim 추출 단계의 원시 산출물. Gemini 또는 휴리스틱이 기사 본문에서 추출한 단일 claim 후보를 담는 계산 계층 DTO다.
 *
 * <p>계약 불변식(compact constructor 가 강제):
 *
 * <ul>
 *   <li>{@link #claimId} 는 null 일 수 없다.
 *   <li>{@link #claimText} 는 null 일 수 없다.
 *   <li>{@link #claimStatusCandidate} 는 null 일 수 없다.
 * </ul>
 *
 * <p>{@link #splitGroup} 은 같은 원 claim 에서 atomic field 분할 묶음을 식별하는 용도이며, null 허용이다. null 이면 독립 단일
 * claim 임을 의미한다.
 *
 * @param claimId claim 고유 식별자 (non-null)
 * @param claimText 추출된 claim 텍스트 (non-null)
 * @param speakerName 발언자 이름 (nullable)
 * @param isQuotedClaim 직접 인용 여부
 * @param originalContext 원문 맥락 (nullable)
 * @param claimStatusCandidate Gemini 또는 휴리스틱이 부여한 status 후보 (non-null)
 * @param splitGroup 같은 원 claim 에서 분할된 경우 묶음 식별자 (nullable)
 */
public record ClaimDraft(
    UUID claimId,
    String claimText,
    String speakerName,
    boolean isQuotedClaim,
    String originalContext,
    ClaimStatusCandidate claimStatusCandidate,
    UUID splitGroup) {

  public ClaimDraft {
    Objects.requireNonNull(claimId, "claimId 는 null 일 수 없다");
    Objects.requireNonNull(claimText, "claimText 는 null 일 수 없다");
    Objects.requireNonNull(claimStatusCandidate, "claimStatusCandidate 는 null 일 수 없다");
  }
}
