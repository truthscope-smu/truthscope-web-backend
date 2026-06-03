package com.truthscope.web.claim.validation;

import com.truthscope.web.scoring.ClaimDraft;
import org.springframework.stereotype.Component;

/**
 * ClaimDraft 의 schema + business rules 검증.
 *
 * <p>Bean Validation (jakarta.validation) + 도메인 규칙. PLAN DISCUSS 5-3 정합:
 *
 * <ul>
 *   <li>claim_text null/blank 거부 (INSUFFICIENT 후보)
 *   <li>claim_text 길이 초과 (1000자 초과) 거부
 *   <li>speaker_name 이 quoted claim 이면 not null 의무 (인용 claim 은 주체 필수)
 *   <li>original_context null 허용 (선택 필드)
 * </ul>
 *
 * <p>본 Validator 는 schema 단계만 검증. ADR-018 §제외 기준 매칭 (OUT_OF_SCOPE 판정) 은 Tier3ReasonValidator (T1-7)
 * 소관.
 */
@Component
public class ClaimSchemaValidator {

  private static final int MAX_CLAIM_TEXT_LENGTH = 1000;

  /**
   * ClaimDraft 의 schema 검증.
   *
   * @return true = schema 통과, false = schema 실패 (INSUFFICIENT 후보로 분류 의무)
   */
  public boolean isValid(ClaimDraft draft) {
    return failureReason(draft) == ValidationFailure.NONE;
  }

  /** Validation 실패 사유 식별 (테스트/디버깅용). */
  public ValidationFailure failureReason(ClaimDraft draft) {
    if (draft == null) {
      return ValidationFailure.NULL_DRAFT;
    }
    if (draft.claimText() == null || draft.claimText().isBlank()) {
      return ValidationFailure.EMPTY_TEXT;
    }
    if (draft.claimText().length() > MAX_CLAIM_TEXT_LENGTH) {
      return ValidationFailure.OVER_LENGTH;
    }
    if (draft.isQuotedClaim() && (draft.speakerName() == null || draft.speakerName().isBlank())) {
      return ValidationFailure.QUOTED_NO_SPEAKER;
    }
    return ValidationFailure.NONE;
  }

  /** Validation 실패 사유 열거. */
  public enum ValidationFailure {
    NONE,
    NULL_DRAFT,
    EMPTY_TEXT,
    OVER_LENGTH,
    QUOTED_NO_SPEAKER
  }
}
