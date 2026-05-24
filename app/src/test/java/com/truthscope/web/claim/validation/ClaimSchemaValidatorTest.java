package com.truthscope.web.claim.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ClaimSchemaValidator 단위 테스트.
 *
 * <p>Spring Context 없이 직접 인스턴스화. PLAN §11-1 5 케이스 + failureReason 매핑 검증.
 */
@DisplayName("ClaimSchemaValidator 단위 테스트")
class ClaimSchemaValidatorTest {

  private ClaimSchemaValidator validator;

  private static final UUID CLAIM_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    validator = new ClaimSchemaValidator();
  }

  private ClaimDraft buildDraft(String claimText, boolean isQuoted, String speakerName) {
    return new ClaimDraft(
        CLAIM_ID,
        claimText == null ? "임시텍스트" : claimText,
        speakerName,
        isQuoted,
        null,
        ClaimStatusCandidate.SCORABLE,
        null);
  }

  // NULL_DRAFT — isValid
  @Test
  @DisplayName("NULL_DRAFT_isValid_false_반환")
  void nullDraft_isValid_false() {
    assertThat(validator.isValid(null)).isFalse();
  }

  // NULL_DRAFT — failureReason
  @Test
  @DisplayName("NULL_DRAFT_failureReason_NULL_DRAFT_반환")
  void nullDraft_failureReason_NULL_DRAFT() {
    assertThat(validator.failureReason(null))
        .isEqualTo(ClaimSchemaValidator.ValidationFailure.NULL_DRAFT);
  }

  // EMPTY_TEXT — isValid
  @Test
  @DisplayName("EMPTY_TEXT_blank_claimText_isValid_false")
  void blankClaimText_isValid_false() {
    // ClaimDraft compact constructor 가 null claimText 거부 → 별도 빌더로 우회 불가
    // blank 입력으로 EMPTY_TEXT 시나리오 검증
    ClaimDraft draft =
        new ClaimDraft(CLAIM_ID, "   ", null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.isValid(draft)).isFalse();
  }

  // EMPTY_TEXT — failureReason
  @Test
  @DisplayName("EMPTY_TEXT_blank_claimText_failureReason_EMPTY_TEXT")
  void blankClaimText_failureReason_EMPTY_TEXT() {
    ClaimDraft draft =
        new ClaimDraft(CLAIM_ID, "   ", null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.failureReason(draft))
        .isEqualTo(ClaimSchemaValidator.ValidationFailure.EMPTY_TEXT);
  }

  // OVER_LENGTH — isValid
  @Test
  @DisplayName("OVER_LENGTH_1001자_claimText_isValid_false")
  void overLength_claimText_isValid_false() {
    String longText = "가".repeat(1001);
    ClaimDraft draft =
        new ClaimDraft(CLAIM_ID, longText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.isValid(draft)).isFalse();
  }

  // OVER_LENGTH — failureReason
  @Test
  @DisplayName("OVER_LENGTH_1001자_claimText_failureReason_OVER_LENGTH")
  void overLength_claimText_failureReason_OVER_LENGTH() {
    String longText = "가".repeat(1001);
    ClaimDraft draft =
        new ClaimDraft(CLAIM_ID, longText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.failureReason(draft))
        .isEqualTo(ClaimSchemaValidator.ValidationFailure.OVER_LENGTH);
  }

  // 경계값 — 정확히 1000자는 통과
  @Test
  @DisplayName("경계값_1000자_claimText_isValid_true")
  void exactly1000chars_isValid_true() {
    String exactText = "가".repeat(1000);
    ClaimDraft draft =
        new ClaimDraft(CLAIM_ID, exactText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.isValid(draft)).isTrue();
  }

  // QUOTED_NO_SPEAKER — isValid
  @Test
  @DisplayName("QUOTED_NO_SPEAKER_isQuoted_true_speaker_null_isValid_false")
  void quotedClaim_nullSpeaker_isValid_false() {
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID, "정부 관계자가 말했다", null, true, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.isValid(draft)).isFalse();
  }

  // QUOTED_NO_SPEAKER — failureReason
  @Test
  @DisplayName("QUOTED_NO_SPEAKER_failureReason_QUOTED_NO_SPEAKER")
  void quotedClaim_nullSpeaker_failureReason_QUOTED_NO_SPEAKER() {
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID, "정부 관계자가 말했다", null, true, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.failureReason(draft))
        .isEqualTo(ClaimSchemaValidator.ValidationFailure.QUOTED_NO_SPEAKER);
  }

  // NONE — 정상 통과
  @Test
  @DisplayName("NONE_정상_ClaimDraft_isValid_true_failureReason_NONE")
  void validDraft_isValid_true_failureReason_NONE() {
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID,
            "정부는 2025년 GDP 성장률이 3%라고 발표했다.",
            "기획재정부",
            true,
            "기획재정부 장관이 기자회견에서 밝혔다.",
            ClaimStatusCandidate.SCORABLE,
            null);
    assertThat(validator.isValid(draft)).isTrue();
    assertThat(validator.failureReason(draft))
        .isEqualTo(ClaimSchemaValidator.ValidationFailure.NONE);
  }

  // 비인용 claim 은 speaker null 허용
  @Test
  @DisplayName("비인용_claim_speaker_null_허용_isValid_true")
  void nonQuotedClaim_speakerNull_isValid_true() {
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID, "수출이 전월 대비 5% 증가했다.", null, false, null, ClaimStatusCandidate.SCORABLE, null);
    assertThat(validator.isValid(draft)).isTrue();
  }
}
