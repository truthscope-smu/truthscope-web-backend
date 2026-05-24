package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimDraftTest {

  private static final UUID CLAIM_ID = UUID.randomUUID();
  private static final String CLAIM_TEXT = "정부는 2025년 GDP 성장률이 3% 라고 발표했다.";

  // (1) compact constructor — claimId null 거부
  @Test
  void compactConstructor_rejectsNullClaimId() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ClaimDraft(
                    null, CLAIM_TEXT, null, false, null, ClaimStatusCandidate.SCORABLE, null))
        .withMessageContaining("claimId 는 null 일 수 없다");
  }

  // (2) compact constructor — claimText null 거부
  @Test
  void compactConstructor_rejectsNullClaimText() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ClaimDraft(
                    CLAIM_ID, null, null, false, null, ClaimStatusCandidate.SCORABLE, null))
        .withMessageContaining("claimText 는 null 일 수 없다");
  }

  // (3) compact constructor — claimStatusCandidate null 거부
  @Test
  void compactConstructor_rejectsNullClaimStatusCandidate() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ClaimDraft(CLAIM_ID, CLAIM_TEXT, null, false, null, null, null))
        .withMessageContaining("claimStatusCandidate 는 null 일 수 없다");
  }

  // (4) splitGroup null 허용 — 독립 단일 claim
  @Test
  void splitGroup_nullable_독립_단일_claim() {
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID,
            CLAIM_TEXT,
            "기획재정부",
            true,
            "기획재정부 장관이 기자회견에서 밝혔다.",
            ClaimStatusCandidate.SCORABLE,
            null);
    assertThat(draft.splitGroup()).isNull();
    assertThat(draft.claimId()).isEqualTo(CLAIM_ID);
    assertThat(draft.claimStatusCandidate()).isEqualTo(ClaimStatusCandidate.SCORABLE);
  }

  // (5) splitGroup non-null 허용 — 분할 묶음
  @Test
  void splitGroup_non_null_분할_묶음_허용() {
    UUID group = UUID.randomUUID();
    ClaimDraft draft =
        new ClaimDraft(
            CLAIM_ID,
            CLAIM_TEXT,
            null,
            false,
            null,
            ClaimStatusCandidate.INSUFFICIENT_CANDIDATE,
            group);
    assertThat(draft.splitGroup()).isEqualTo(group);
  }
}
