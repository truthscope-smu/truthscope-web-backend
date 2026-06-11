package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.exception.ConflictException;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.exception.TooManyRequestsException;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ReVerifyPolicy;
import com.truthscope.web.scoring.SourceTransparency;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * ReVerifyTransactionService 단위 테스트.
 *
 * <p>U6: 역도출 메서드 단위 검증 + validateAndGet 404/409/429 분기 커버. U7: 쿨다운 판정 — verifiedAt 9분 전 = 429, 11분
 * 전 = 통과, lastConfirmedAt 이 더 최신이면 그 기준.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReVerifyTransactionService 단위 테스트")
class ReVerifyTransactionServiceTest {

  @Mock private VerificationResultRepository verificationResultRepository;

  @Mock private EntityManager entityManager;

  private ReVerifyPolicy policy;
  private ReVerifyTransactionService service;

  @BeforeEach
  void setUp() {
    // 쿨다운 10분, scoreDriftThreshold=15, urlReplacementRatio=0.3
    policy = new ReVerifyPolicy(Duration.ofMinutes(10), 15, 0.3);
    // ReVerifyTransactionService는 추가 의존성이 있으나 validateAndGet 테스트만 필요한 것은 null-safe
    service =
        new ReVerifyTransactionService(
            verificationResultRepository,
            null, // verifySourceRepository — validateAndGet에서 미사용
            null, // claimRepository — validateAndGet에서 미사용
            null, // analysisSessionRepository — validateAndGet에서 미사용
            null, // articleScorePolicy
            null, // scoreBandPolicy
            entityManager,
            policy);
  }

  // ─── validateAndGet 분기 테스트 ──────────────────────────────────────────────

  @Nested
  @DisplayName("U6: validateAndGet — 404/409/429 분기")
  class ValidateAndGetTest {

    @Test
    @DisplayName("결과가 존재하지 않으면 NotFoundException(404)")
    void 결과_없으면_404() {
      UUID resultId = UUID.randomUUID();
      when(verificationResultRepository.findWithClaimById(resultId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.validateAndGet(resultId))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("검증 결과를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("이미 supersede된 결과(isCurrent=false)이면 ConflictException(409)")
    void 이미_정정된_결과면_409() {
      UUID resultId = UUID.randomUUID();
      VerificationResult supersededResult = buildSupersededResult(resultId);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(supersededResult));

      assertThatThrownBy(() -> service.validateAndGet(resultId))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("이미 정정된 결과");
    }

    @Test
    @DisplayName("verifiedAt이 9분 전이면 쿨다운 미충족 — TooManyRequestsException(429)")
    void verifiedAt_9분전_429() {
      UUID resultId = UUID.randomUUID();
      LocalDateTime nineMinutesAgo = LocalDateTime.now().minusMinutes(9);
      VerificationResult result = buildCurrentResult(resultId, nineMinutesAgo, null);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(result));

      assertThatThrownBy(() -> service.validateAndGet(resultId))
          .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("verifiedAt이 11분 전이면 쿨다운 충족 — ReVerifyTarget 반환")
    void verifiedAt_11분전_통과() {
      UUID resultId = UUID.randomUUID();
      LocalDateTime elevenMinutesAgo = LocalDateTime.now().minusMinutes(11);
      VerificationResult result = buildCurrentResult(resultId, elevenMinutesAgo, null);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(result));

      ReVerifyTransactionService.ReVerifyTarget target = service.validateAndGet(resultId);

      assertThat(target.resultId()).isEqualTo(resultId);
      assertThat(target.claimId()).isNotNull();
    }
  }

  // ─── U7: 쿨다운 판정 — lastConfirmedAt 우선 ──────────────────────────────────

  @Nested
  @DisplayName("U7: 쿨다운 기준 — verifiedAt vs lastConfirmedAt 최신값")
  class CooldownTest {

    @Test
    @DisplayName("lastConfirmedAt이 verifiedAt보다 최신이고 9분 전이면 429")
    void lastConfirmedAt이_더_최신이고_9분전_429() {
      UUID resultId = UUID.randomUUID();
      // verifiedAt은 30분 전 (쿨다운 충족), lastConfirmedAt은 9분 전 (쿨다운 미충족)
      LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
      LocalDateTime nineMinutesAgo = LocalDateTime.now().minusMinutes(9);
      VerificationResult result = buildCurrentResult(resultId, thirtyMinutesAgo, nineMinutesAgo);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(result));

      assertThatThrownBy(() -> service.validateAndGet(resultId))
          .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("lastConfirmedAt이 null이면 verifiedAt만 기준 — 11분 전이면 통과")
    void lastConfirmedAt_null이면_verifiedAt_기준_통과() {
      UUID resultId = UUID.randomUUID();
      LocalDateTime elevenMinutesAgo = LocalDateTime.now().minusMinutes(11);
      VerificationResult result = buildCurrentResult(resultId, elevenMinutesAgo, null);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(result));

      ReVerifyTransactionService.ReVerifyTarget target = service.validateAndGet(resultId);

      assertThat(target.resultId()).isEqualTo(resultId);
    }

    @Test
    @DisplayName("verifiedAt이 최신이고 9분 전이면 429 — lastConfirmedAt이 더 오래됐어도")
    void verifiedAt이_최신이고_9분전_429() {
      UUID resultId = UUID.randomUUID();
      // lastConfirmedAt은 30분 전, verifiedAt은 9분 전
      LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
      LocalDateTime nineMinutesAgo = LocalDateTime.now().minusMinutes(9);
      VerificationResult result = buildCurrentResult(resultId, nineMinutesAgo, thirtyMinutesAgo);
      when(verificationResultRepository.findWithClaimById(resultId))
          .thenReturn(Optional.of(result));

      assertThatThrownBy(() -> service.validateAndGet(resultId))
          .isInstanceOf(TooManyRequestsException.class);
    }
  }

  // ─── U6: 역도출 — ClaimVerificationSignal 생성 정합성 ──────────────────────────

  @Nested
  @DisplayName("U6: 역도출 메서드 단위 검증")
  class ReverseDerivationTest {

    @Test
    @DisplayName("score 있는 결과 역도출 — status=SCORABLE, tier=1, transparency=EXPLICIT")
    void 점수_있는_결과_역도출_SCORABLE() {
      // score=80, tier=1 → SCORABLE, EXPLICIT
      var signal =
          ReVerifyTransactionService.deriveSignalFromResult(
              (short) 1, (short) 80, null /* tier3Reason */);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
      assertThat(signal.score()).isEqualTo(80);
      assertThat(signal.tier()).isEqualTo((short) 1);
      assertThat(signal.sourceTransparency()).isEqualTo(SourceTransparency.EXPLICIT);
    }

    @Test
    @DisplayName("score 있는 결과 역도출 — tier=2, transparency=AMBIGUOUS")
    void 점수_있는_결과_역도출_tier2_AMBIGUOUS() {
      var signal = ReVerifyTransactionService.deriveSignalFromResult((short) 2, (short) 55, null);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
      assertThat(signal.score()).isEqualTo(55);
      assertThat(signal.sourceTransparency()).isEqualTo(SourceTransparency.AMBIGUOUS);
    }

    @Test
    @DisplayName("score null + tier3Reason=INSUFFICIENT → status=INSUFFICIENT, NONE")
    void score_null_INSUFFICIENT() {
      var signal =
          ReVerifyTransactionService.deriveSignalFromResult(
              (short) 3, null, com.truthscope.web.entity.enums.Tier3Reason.INSUFFICIENT);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.INSUFFICIENT);
      assertThat(signal.score()).isNull();
      assertThat(signal.sourceTransparency()).isEqualTo(SourceTransparency.NONE);
    }

    @Test
    @DisplayName("score null + tier3Reason=TIME_SENSITIVE → status=TIME_SENSITIVE")
    void score_null_TIME_SENSITIVE() {
      var signal =
          ReVerifyTransactionService.deriveSignalFromResult(
              (short) 3, null, com.truthscope.web.entity.enums.Tier3Reason.TIME_SENSITIVE);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.TIME_SENSITIVE);
    }

    @Test
    @DisplayName("score null + tier3Reason=OUT_OF_SCOPE → status=OUT_OF_SCOPE")
    void score_null_OUT_OF_SCOPE() {
      var signal =
          ReVerifyTransactionService.deriveSignalFromResult(
              (short) 3, null, com.truthscope.web.entity.enums.Tier3Reason.OUT_OF_SCOPE);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("score null + tier3Reason=null (레거시) → status=INSUFFICIENT 폴백")
    void score_null_tier3Reason_null_레거시_폴백() {
      var signal = ReVerifyTransactionService.deriveSignalFromResult((short) 3, null, null);

      assertThat(signal.status()).isEqualTo(ClaimScoreStatus.INSUFFICIENT);
    }
  }

  // ─── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

  /** 이미 supersede된 결과를 모킹 — isCurrent()=false */
  private VerificationResult buildSupersededResult(UUID resultId) {
    Claim claim = mock(Claim.class);
    when(claim.getId()).thenReturn(UUID.randomUUID());

    VerificationResult result = mock(VerificationResult.class);
    when(result.getId()).thenReturn(resultId);
    when(result.getClaim()).thenReturn(claim);
    when(result.isCurrent()).thenReturn(false);
    return result;
  }

  /**
   * 현재 유효한 결과를 모킹. verifiedAt과 lastConfirmedAt을 지정한다.
   *
   * @param resultId 결과 ID
   * @param verifiedAt 최초 검증 시각
   * @param lastConfirmedAt 마지막 재확인 시각 (null 가능)
   */
  private VerificationResult buildCurrentResult(
      UUID resultId, LocalDateTime verifiedAt, LocalDateTime lastConfirmedAt) {
    Claim claim = mock(Claim.class);
    when(claim.getId()).thenReturn(UUID.randomUUID());

    VerificationResult result = mock(VerificationResult.class);
    when(result.getId()).thenReturn(resultId);
    when(result.getClaim()).thenReturn(claim);
    when(result.isCurrent()).thenReturn(true);
    when(result.getVerifiedAt()).thenReturn(verifiedAt);
    when(result.getLastConfirmedAt()).thenReturn(lastConfirmedAt);
    return result;
  }
}
