package com.truthscope.web.service.verification;

import com.truthscope.web.adapter.factcheck.GoogleFcAdapter;
import com.truthscope.web.claim.validation.HeuristicValidator;
import com.truthscope.web.claim.validation.Tier3ReasonValidator;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.scoring.CascadePolicy;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreCalculator;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.SourceTransparency;
import com.truthscope.web.url.UrlValidator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 3-Tier Cascade orchestrator (ADR-013 옵션 A lock). Tier 1 -> Tier 2 -> Tier 3 단락 흐름.
 *
 * <p>입력: List&lt;ClaimDraft&gt; (Wave 1 ClaimAnalysisService 산출물).
 *
 * <p>출력: List&lt;ClaimVerificationSignal&gt; (Phase 55 4 함수 입력 contract).
 *
 * <p>@Transactional 클래스 레벨 없음 (R2-2/CX2-5 amend lock): 외부 HTTP 호출 (Tier 1/2/3 cascade) 은 트랜잭션 밖.
 * 영속화는 AnalysisTransactionService 의 persistCascadeResults 위임. Supabase pooler connection 점유 차단.
 */
@Service
@RequiredArgsConstructor
public class VerificationCascadeService {

  private final FactcheckCacheRepository factcheckCacheRepository;
  private final GoogleFcAdapter googleFcAdapter;
  private final HybridCascadeService hybridCascade;
  private final UrlValidator urlValidator;
  private final ClaimScoreCalculator policyScorer;
  private final ClaimScoreCalculator stanceScorer;
  private final Tier3ReasonValidator tier3Validator;
  private final CascadePolicy cascadePolicy;
  private final ClaimAttributionService attributionService;

  /**
   * 주어진 ClaimDraft 목록을 3-Tier Cascade 로 검증하고 ClaimCascadeResult 목록을 반환한다.
   *
   * @param drafts Wave 1 ClaimAnalysisService 가 추출한 draft 목록
   * @return 각 draft 에 대응하는 ClaimCascadeResult 목록 (순서 보존)
   */
  public List<ClaimCascadeResult> cascade(List<ClaimDraft> drafts) {
    return drafts.stream().map(this::cascadeOne).toList();
  }

  /**
   * 단일 ClaimDraft 를 3-Tier Cascade 로 검증한다.
   *
   * <p>흐름:
   *
   * <ol>
   *   <li>Tier 1: factcheck_cache 전문 검색 -> 히트 시 SCORABLE (score=100, EXPLICIT), evidence=빈 리스트
   *   <li>Tier 1': Google FC API stub (v1.x 항상 empty, bean 보존 목적)
   *   <li>Tier 2: HybridCascadeService retrieve -> URL 검증 -> PolicyEvidenceScorer /
   *       StanceRatioScorer. 성공 시 ClaimCascadeResult(signal, validSnapshots) 반환.
   *   <li>Tier 3: Tier3ReasonValidator -> 비판정 신호, evidence=빈 리스트
   * </ol>
   *
   * <p>attribution 부착은 v1.x 에서 스킵 (DB 부하 절약). µ2.4 persistCascadeResults 에서 처리.
   *
   * @param draft 검증 대상 ClaimDraft
   * @return ClaimCascadeResult (signal compact constructor 불변식 보장, Tier 2 경우 evidence 포함)
   */
  private ClaimCascadeResult cascadeOne(ClaimDraft draft) {

    // Tier 1: factcheck_cache 전문 검색
    List<?> cacheHits = factcheckCacheRepository.searchByText(draft.claimText());
    if (!cacheHits.isEmpty()) {
      return new ClaimCascadeResult(
          new ClaimVerificationSignal(
              draft.claimId(),
              (short) 1,
              100,
              ClaimScoreStatus.SCORABLE,
              SourceTransparency.EXPLICIT),
          List.of());
    }

    // Tier 1': Google FC API 진입점 — v1.x 미라우팅 (bean은 field 주입으로 보존, 호출 X).
    //   ADR-018 §결정 5 활성화 시 이 지점에서 googleFcAdapter.findMatching(draft.claimText()) 호출 + rating 매핑.

    // Tier 2: HybridCascadeService -> URL 검증 -> 점수 산출
    List<EvidenceSnapshot> snapshots = hybridCascade.retrieve(draft.claimText(), 5);
    List<EvidenceSnapshot> validSnapshots =
        snapshots.stream().filter(s -> urlValidator.validate(s.url())).toList();

    Optional<ClaimCascadeResult> tier2Result = tryTier2Score(draft, validSnapshots);
    if (tier2Result.isPresent()) {
      return tier2Result.get();
    }

    // Tier 3: Tier3ReasonValidator -> 비판정 신호, evidence 없음
    return new ClaimCascadeResult(buildTier3Signal(draft), List.of());
  }

  /**
   * Tier 2 점수 산출을 시도한다. PolicyEvidenceScorer 우선, StanceRatioScorer fallback. 출처 수 임계값 미충족 또는 두
   * scorer 모두 실패 시 empty 반환.
   */
  private Optional<ClaimCascadeResult> tryTier2Score(
      ClaimDraft draft, List<EvidenceSnapshot> validSnapshots) {
    if (validSnapshots.size() < cascadePolicy.sourceCountThreshold()) {
      return Optional.empty();
    }

    Optional<Integer> policyScore = policyScorer.calculate(draft, validSnapshots, cascadePolicy);
    if (policyScore.isPresent()) {
      return Optional.of(
          new ClaimCascadeResult(
              new ClaimVerificationSignal(
                  draft.claimId(),
                  (short) 2,
                  policyScore.get(),
                  ClaimScoreStatus.SCORABLE,
                  SourceTransparency.AMBIGUOUS),
              validSnapshots));
    }

    Optional<Integer> stanceScore = stanceScorer.calculate(draft, validSnapshots, cascadePolicy);
    if (stanceScore.isPresent()) {
      return Optional.of(
          new ClaimCascadeResult(
              new ClaimVerificationSignal(
                  draft.claimId(),
                  (short) 2,
                  stanceScore.get(),
                  ClaimScoreStatus.SCORABLE,
                  SourceTransparency.AMBIGUOUS),
              validSnapshots));
    }

    return Optional.empty();
  }

  /**
   * Tier 3 비판정 ClaimVerificationSignal 을 생성한다.
   *
   * <p>Tier3ReasonValidator 의 결과(HeuristicValidator.Tier3ReasonCandidate)를 ClaimScoreStatus 로 매핑한다:
   *
   * <ul>
   *   <li>OUT_OF_SCOPE -> ClaimScoreStatus.OUT_OF_SCOPE
   *   <li>TIME_SENSITIVE -> ClaimScoreStatus.TIME_SENSITIVE
   *   <li>INSUFFICIENT 또는 empty -> ClaimScoreStatus.INSUFFICIENT (기본값)
   * </ul>
   *
   * <p>score = null, tier = 3, sourceTransparency = NONE (compact constructor 불변식 준수).
   */
  private ClaimVerificationSignal buildTier3Signal(ClaimDraft draft) {
    ClaimScoreStatus status =
        tier3Validator
            .validate(draft)
            .map(this::toClaimScoreStatus)
            .orElse(ClaimScoreStatus.INSUFFICIENT);

    return new ClaimVerificationSignal(
        draft.claimId(), (short) 3, null, status, SourceTransparency.NONE);
  }

  /**
   * HeuristicValidator.Tier3ReasonCandidate 를 ClaimScoreStatus 로 변환한다.
   *
   * @param candidate Tier3ReasonValidator 가 반환한 reason 후보
   * @return 대응하는 ClaimScoreStatus
   */
  private ClaimScoreStatus toClaimScoreStatus(HeuristicValidator.Tier3ReasonCandidate candidate) {
    return switch (candidate) {
      case OUT_OF_SCOPE -> ClaimScoreStatus.OUT_OF_SCOPE;
      case TIME_SENSITIVE -> ClaimScoreStatus.TIME_SENSITIVE;
      case INSUFFICIENT -> ClaimScoreStatus.INSUFFICIENT;
    };
  }
}
