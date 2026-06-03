package com.truthscope.web.service.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.claim.validation.HeuristicValidator;
import com.truthscope.web.claim.validation.Tier3ReasonValidator;
import com.truthscope.web.entity.FactcheckCache;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.scoring.CascadePolicy;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreCalculator;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.url.UrlValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VerificationCascadeService 단위 테스트.
 *
 * <p>PLAN §11-1 시나리오: Tier 1->2->3 단락, ClaimVerificationSignal compact constructor 통과.
 *
 * <p>테스트 메서드명은 PLAN §11-4 회귀 시뮬레이션 expected fail matrix (lines 1389-1399) 와 일치한다. µ2.6 무력화 A 시뮬레이션
 * stdout 비교에서 이 이름을 그대로 사용한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VerificationCascadeService 단위 테스트")
class VerificationCascadeServiceTest {

  @Mock private FactcheckCacheRepository factcheckCacheRepository;
  @Mock private HybridCascadeService hybridCascade;
  @Mock private UrlValidator urlValidator;
  @Mock private ClaimScoreCalculator policyScorer;
  @Mock private ClaimScoreCalculator stanceScorer;
  @Mock private Tier3ReasonValidator tier3Validator;
  @Mock private ClaimAttributionService attributionService;

  private CascadePolicy cascadePolicy;
  private VerificationCascadeService service;

  @BeforeEach
  void setUp() {
    cascadePolicy = new CascadePolicy(3, true, 50, List.of("수치", "일자", "대상", "금액", "제도명"));
    service =
        new VerificationCascadeService(
            factcheckCacheRepository,
            hybridCascade,
            urlValidator,
            policyScorer,
            stanceScorer,
            tier3Validator,
            cascadePolicy,
            attributionService);
  }

  // -----------------------------------------------------------------------------------------
  // 헬퍼 팩토리
  // -----------------------------------------------------------------------------------------

  private ClaimDraft buildDraft(String text) {
    return new ClaimDraft(
        UUID.randomUUID(), text, null, false, null, ClaimStatusCandidate.SCORABLE, null);
  }

  private EvidenceSnapshot buildSnapshot(String url) {
    return new EvidenceSnapshot(
        url,
        "publisher",
        "title",
        "SUPPORTED",
        java.util.Map.of(),
        java.util.Collections.emptyMap());
  }

  // -----------------------------------------------------------------------------------------
  // 시나리오
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("cascade_returnsSignalListForGivenClaims")
  void cascade_returnsSignalListForGivenClaims() {
    ClaimDraft draft = buildDraft("정부는 2025년 GDP 3% 성장을 발표했다.");
    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());
    when(tier3Validator.validate(any())).thenReturn(Optional.empty());

    List<ClaimCascadeResult> result = service.cascade(List.of(draft));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).signal().claimId()).isEqualTo(draft.claimId());
  }

  @Test
  @DisplayName("cascade_routesTier1HitToScorable")
  void cascade_routesTier1HitToScorable() {
    ClaimDraft draft = buildDraft("팩트체크 캐시에 있는 claim");
    FactcheckCache mockCache = mock(FactcheckCache.class);
    when(factcheckCacheRepository.searchByText(draft.claimText())).thenReturn(List.of(mockCache));

    List<ClaimCascadeResult> result = service.cascade(List.of(draft));

    assertThat(result).hasSize(1);
    ClaimVerificationSignal signal = result.get(0).signal();
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
    assertThat(signal.tier()).isEqualTo((short) 1);
    assertThat(signal.score()).isNotNull().isBetween(0, 100);
    // Tier 1 히트 시 HybridCascadeService 호출 안 됨
    verify(hybridCascade, never()).retrieve(anyString(), anyInt());
  }

  @Test
  @DisplayName("cascade_routesNoCacheToTier2")
  void cascade_routesNoCacheToTier2() {
    ClaimDraft draft = buildDraft("캐시 없는 claim");
    EvidenceSnapshot snap1 =
        new EvidenceSnapshot(
            "https://example.com/a",
            "pub-a",
            "title-a",
            "SUPPORTED",
            Map.of(),
            java.util.Collections.emptyMap());
    EvidenceSnapshot snap2 =
        new EvidenceSnapshot(
            "https://example.com/b",
            "pub-b",
            "title-b",
            "SUPPORTED",
            Map.of(),
            java.util.Collections.emptyMap());
    EvidenceSnapshot snap3 =
        new EvidenceSnapshot(
            "https://example.com/c",
            "pub-c",
            "title-c",
            "SUPPORTED",
            Map.of(),
            java.util.Collections.emptyMap());
    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of(snap1, snap2, snap3));
    when(urlValidator.validate(anyString())).thenReturn(true);
    when(policyScorer.calculate(any(), anyList(), any())).thenReturn(Optional.of(75));

    List<ClaimCascadeResult> result = service.cascade(List.of(draft));

    // cache miss → HybridCascade → policyScorer 경로 → Tier 2 SCORABLE 신호 산출
    verify(hybridCascade, atLeastOnce()).retrieve(eq(draft.claimText()), anyInt());
    assertThat(result).hasSize(1);
    ClaimVerificationSignal signal = result.get(0).signal();
    assertThat(signal.tier()).isEqualTo((short) 2);
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.SCORABLE);
    assertThat(signal.score()).isEqualTo(75);
  }

  @Test
  @DisplayName("cascade_routesUnsufficientToTier3")
  void cascade_routesUnsufficientToTier3() {
    ClaimDraft draft = buildDraft("검증 불가 claim");
    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());
    when(tier3Validator.validate(any()))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.INSUFFICIENT));

    List<ClaimCascadeResult> result = service.cascade(List.of(draft));

    assertThat(result).hasSize(1);
    ClaimVerificationSignal signal = result.get(0).signal();
    assertThat(signal.status()).isEqualTo(ClaimScoreStatus.INSUFFICIENT);
    assertThat(signal.tier()).isEqualTo((short) 3);
    // 비판정 claim 은 score null (compact constructor 불변식)
    assertThat(signal.score()).isNull();
  }

  /**
   * cascade 출력 ClaimVerificationSignal 이 입력 ClaimDraft 의 claimId 만 보존하고 attribution 3 필드
   * (speakerName / isQuotedClaim / originalContext)는 signal 에 노출하지 않음을 확인한다.
   *
   * <p>설계 의도: attribution 은 µ2.4 persistCascadeResults (AnalysisTransactionService) 가 Claim entity
   * 에 영속화하는 별 경로 — signal record 에 의도적으로 attribution 필드 없음 (컴파일 시점 차단).
   *
   * <p>검증 대상: (a) result.size() == 입력 draft 수, (b) result.get(0).claimId() == 입력 draft.claimId(). 본
   * test 가 PASS 라는 것은 cascade 흐름이 attribution 입력에 의해 오류 없이 통과한다는 의미 (PLAN §11-4 무력화 A 시뮬레이션 시
   * cascade 진입 차단 → 본 test FAIL 로 매핑).
   */
  @Test
  @DisplayName("cascade_preservesClaimAttribution")
  void cascade_preservesClaimAttribution() {
    // attribution 필드가 포함된 ClaimDraft 생성
    ClaimDraft draftWithAttribution =
        new ClaimDraft(
            UUID.randomUUID(),
            "발언자 있는 claim",
            "홍길동", // speakerName
            true, // isQuotedClaim
            "원문 맥락", // originalContext
            ClaimStatusCandidate.SCORABLE,
            null);

    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());
    when(tier3Validator.validate(any())).thenReturn(Optional.empty());

    List<ClaimCascadeResult> result = service.cascade(List.of(draftWithAttribution));

    // ClaimVerificationSignal 에는 attribution 필드가 없으므로 컴파일 수준에서 전파 불가
    // claimId 만 전파됨을 확인
    assertThat(result).hasSize(1);
    assertThat(result.get(0).signal().claimId()).isEqualTo(draftWithAttribution.claimId());
  }

  @Test
  @DisplayName("cascade_producesSignalForEachDraft")
  void cascade_producesSignalForEachDraft() {
    ClaimDraft draft1 = buildDraft("claim 1");
    ClaimDraft draft2 = buildDraft("claim 2");
    ClaimDraft draft3 = buildDraft("claim 3");

    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());
    when(tier3Validator.validate(any())).thenReturn(Optional.empty());

    List<ClaimCascadeResult> result = service.cascade(List.of(draft1, draft2, draft3));

    assertThat(result).hasSize(3);
    // 각 signal 의 claimId 가 입력 draft 와 1:1 대응
    assertThat(result.get(0).signal().claimId()).isEqualTo(draft1.claimId());
    assertThat(result.get(1).signal().claimId()).isEqualTo(draft2.claimId());
    assertThat(result.get(2).signal().claimId()).isEqualTo(draft3.claimId());
  }

  @Test
  @DisplayName("cascade_handlesEmptyDrafts")
  void cascade_handlesEmptyDrafts() {
    List<ClaimCascadeResult> result = service.cascade(List.of());

    assertThat(result).isEmpty();
    verify(factcheckCacheRepository, never()).searchByText(anyString());
    verify(hybridCascade, never()).retrieve(anyString(), anyInt());
  }

  @Test
  @DisplayName("cascade_passesPolicyToScorer")
  void cascade_passesPolicyToScorer() {
    // sourceCountThreshold = 3 이므로 3개 이상의 유효 snapshot 이 있어야 policyScorer 호출
    ClaimDraft draft = buildDraft("정책 근거 있는 claim");
    EvidenceSnapshot s1 = buildSnapshot("https://example1.com");
    EvidenceSnapshot s2 = buildSnapshot("https://example2.com");
    EvidenceSnapshot s3 = buildSnapshot("https://example3.com");

    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());
    when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of(s1, s2, s3));
    when(urlValidator.validate(anyString())).thenReturn(true);
    when(policyScorer.calculate(any(), any(), any())).thenReturn(Optional.of(80));

    service.cascade(List.of(draft));

    // policyScorer.calculate 가 cascadePolicy 와 함께 호출되었음을 검증
    verify(policyScorer, atLeastOnce()).calculate(eq(draft), any(), eq(cascadePolicy));
  }
}
