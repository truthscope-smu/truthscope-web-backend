package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimVerificationSignalTest {

  private static final UUID CLAIM_ID = UUID.randomUUID();
  private static final Short TIER = (short) 1;

  // (1) SCORABLE + score 0/50/100 생성 성공
  @Test
  void scorable_score_0_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, 0, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT);
    assertThat(signal.score()).isEqualTo(0);
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
  }

  @Test
  void scorable_score_50_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, 50, ClaimScoreStatus.SCORABLE, SourceTransparency.AMBIGUOUS);
    assertThat(signal.score()).isEqualTo(50);
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
  }

  @Test
  void scorable_score_100_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, 100, ClaimScoreStatus.SCORABLE, SourceTransparency.NONE);
    assertThat(signal.score()).isEqualTo(100);
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
  }

  // (2) SCORABLE + score null → IllegalArgumentException
  @Test
  void scorable_score_null이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    CLAIM_ID, TIER, null, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT))
        .withMessageContaining("SCORABLE claim은 0..100 score가 필수다");
  }

  // (3) SCORABLE + score 101 → IAE
  @Test
  void scorable_score_101이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    CLAIM_ID, TIER, 101, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT))
        .withMessageContaining("SCORABLE claim은 0..100 score가 필수다");
  }

  // (4) SCORABLE + score -1 → IAE
  @Test
  void scorable_score_마이너스1이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    CLAIM_ID, TIER, -1, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT))
        .withMessageContaining("SCORABLE claim은 0..100 score가 필수다");
  }

  // (5) 비판정 3종(INSUFFICIENT/TIME_SENSITIVE/OUT_OF_SCOPE) + score null 생성 성공
  @Test
  void insufficient_score_null_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, null, ClaimScoreStatus.INSUFFICIENT, SourceTransparency.EXPLICIT);
    assertThat(signal.score()).isNull();
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.INSUFFICIENT);
  }

  @Test
  void time_sensitive_score_null_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, null, ClaimScoreStatus.TIME_SENSITIVE, SourceTransparency.AMBIGUOUS);
    assertThat(signal.score()).isNull();
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.TIME_SENSITIVE);
  }

  @Test
  void out_of_scope_score_null_생성_성공() {
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            CLAIM_ID, TIER, null, ClaimScoreStatus.OUT_OF_SCOPE, SourceTransparency.NONE);
    assertThat(signal.score()).isNull();
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.OUT_OF_SCOPE);
  }

  // (6) INSUFFICIENT + score 50 → IAE
  @Test
  void insufficient_score_50이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    CLAIM_ID, TIER, 50, ClaimScoreStatus.INSUFFICIENT, SourceTransparency.EXPLICIT))
        .withMessageContaining("비판정 claim은 score가 null이어야 한다");
  }

  // (7) claimId/tier/status/sourceTransparency null → NullPointerException
  @Test
  void claimId_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    null, TIER, 50, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT))
        .withMessageContaining("claimId는 null일 수 없다");
  }

  @Test
  void tier_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(
                    CLAIM_ID, null, 50, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT))
        .withMessageContaining("tier는 null일 수 없다");
  }

  @Test
  void status_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new ClaimVerificationSignal(CLAIM_ID, TIER, 50, null, SourceTransparency.EXPLICIT))
        .withMessageContaining("status는 null일 수 없다");
  }

  @Test
  void sourceTransparency_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new ClaimVerificationSignal(CLAIM_ID, TIER, 50, ClaimScoreStatus.SCORABLE, null))
        .withMessageContaining("sourceTransparency는 null일 수 없다");
  }
}
