package com.truthscope.web.service;

import com.truthscope.web.converter.VerifySourceConverter;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import com.truthscope.web.entity.enums.SupersedeReason;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.exception.ConflictException;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.exception.TooManyRequestsException;
import com.truthscope.web.factory.VerificationResultFactory;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.repository.VerifySourceRepository;
import com.truthscope.web.scoring.ArticleFactScore;
import com.truthscope.web.scoring.ArticleFactScoreAggregator;
import com.truthscope.web.scoring.ArticleScorePolicy;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.CoverageAggregator;
import com.truthscope.web.scoring.CoverageSummary;
import com.truthscope.web.scoring.ReVerifyPolicy;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.scoring.SourceTransparency;
import com.truthscope.web.scoring.SupersedeDecider;
import com.truthscope.web.scoring.TruthLabel;
import com.truthscope.web.scoring.TruthLabelDeriver;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재검증(re-verify) 결과 영속화를 담당하는 트랜잭션 전용 서비스.
 *
 * <p>validateAndGet: 재검증 대상 결과 유효성 검사(404/409/429 분기). persistReverifyOutcome: advisory lock +
 * supersede 체인 + 세션 재집계.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReVerifyTransactionService {

  private final VerificationResultRepository verificationResultRepository;
  private final VerifySourceRepository verifySourceRepository;
  private final ClaimRepository claimRepository;
  private final AnalysisSessionRepository analysisSessionRepository;
  private final ArticleScorePolicy articleScorePolicy;
  private final ScoreBandPolicy scoreBandPolicy;
  private final EntityManager entityManager;
  private final ReVerifyPolicy policy;

  /**
   * 재검증 대상과 claimId 를 담는 결과 레코드.
   *
   * @param resultId 재검증 대상 VerificationResult ID
   * @param claimId 연결된 Claim ID
   */
  public record ReVerifyTarget(UUID resultId, UUID claimId) {}

  /**
   * 재검증 요청 유효성 검사 후 ReVerifyTarget 을 반환한다.
   *
   * <ol>
   *   <li>결과 미존재 → NotFoundException(404)
   *   <li>이미 supersede 됨 → ConflictException(409)
   *   <li>쿨다운 미충족 → TooManyRequestsException(429)
   * </ol>
   *
   * @param resultId 재검증 대상 VerificationResult ID
   * @return 유효성 검사 통과 시 ReVerifyTarget
   */
  @Transactional(readOnly = true)
  public ReVerifyTarget validateAndGet(UUID resultId) {
    VerificationResult result =
        verificationResultRepository
            .findWithClaimById(resultId)
            .orElseThrow(() -> new NotFoundException("검증 결과를 찾을 수 없습니다"));

    if (!result.isCurrent()) {
      throw new ConflictException("이미 정정된 결과입니다. 최신 결과에 재검증을 요청하세요");
    }

    // 쿨다운 기준: verifiedAt 과 lastConfirmedAt 중 더 최신값
    LocalDateTime lastActivity = latestOf(result.getVerifiedAt(), result.getLastConfirmedAt());
    LocalDateTime cooldownBoundary = LocalDateTime.now().minus(policy.cooldown());
    if (lastActivity != null && lastActivity.isAfter(cooldownBoundary)) {
      throw new TooManyRequestsException(
          "재검증 쿨다운 중입니다. " + policy.cooldown().toMinutes() + "분 후에 다시 시도하세요");
    }

    return new ReVerifyTarget(resultId, result.getClaim().getId());
  }

  /**
   * 재검증 결과를 영속화한다.
   *
   * <p>advisory lock 획득 실패 시 no-op (호출자가 @Async 가정). supersede 4조건 판별 후 변경 없으면 confirmRecheck, 변경
   * 있으면 supersede 체인 → 세션 재집계 순으로 처리한다.
   *
   * <p>PLAN rev.2 순서 의무:
   *
   * <ol>
   *   <li>advisory lock — 실패 시 return
   *   <li>기존 행 재조회(체인 JOIN FETCH) — isCurrent() false 면 return
   *   <li>입력 도출(score/tier/label/oldUrls/newUrls)
   *   <li>SupersedeDecider.decide — empty 면 confirmRecheck 후 return; present 면 4a → 4d 순서 영속화
   *   <li>세션 재집계
   * </ol>
   *
   * @param oldResultId 재검증 전 기존 결과 ID
   * @param newResult 재검증 cascade 결과
   */
  // CHECKSTYLE:OFF
  @Transactional
  public void persistReverifyOutcome(UUID oldResultId, ClaimCascadeResult newResult) {

    // 1. advisory lock: pg_try_advisory_xact_lock — false 이면 동시 요청 no-op
    Object lockResult =
        entityManager
            .createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(hashtext('reverify-' || cast(:id as text)))")
            .setParameter("id", oldResultId)
            .getSingleResult();
    if (Boolean.FALSE.equals(lockResult)) {
      log.info("재검증 advisory lock 획득 실패 — 동시 요청으로 skip: resultId={}", oldResultId);
      return;
    }

    // 2. 기존 행 재조회(체인 JOIN FETCH) — 늦은 동시 요청 no-op
    VerificationResult old =
        verificationResultRepository
            .findWithChainById(oldResultId)
            .orElseThrow(() -> new NotFoundException("재검증 대상 결과를 찾을 수 없습니다: " + oldResultId));
    if (!old.isCurrent()) {
      log.info("재검증 대상이 이미 supersede 됨 — 늦은 동시 요청으로 skip: resultId={}", oldResultId);
      return;
    }

    // 3. 입력 도출
    ClaimVerificationSignal newSignal = newResult.signal();
    // claim 일치성 가드: 다른 claim 의 결과가 이 체인에 저장되는 무결성 오염 차단 (CodeRabbit #113)
    if (!newSignal.claimId().equals(old.getClaim().getId())) {
      log.error(
          "재검증 결과 claim 불일치 — 체인 오염 차단: resultId={}, expectedClaimId={}, actualClaimId={}",
          oldResultId,
          old.getClaim().getId(),
          newSignal.claimId());
      return;
    }
    Integer newScore = newSignal.score();
    short newTier = newSignal.tier();
    Integer oldScore = old.getScore() != null ? (int) old.getScore() : null;
    short oldTier = old.getTier();

    TruthLabel newLabel =
        newScore != null ? TruthLabelDeriver.deriveTruthLabel(newScore, scoreBandPolicy) : null;
    TruthLabel oldLabel =
        oldScore != null ? TruthLabelDeriver.deriveTruthLabel(oldScore, scoreBandPolicy) : null;

    Set<String> oldUrls =
        verifySourceRepository.findByResultIdIn(List.of(oldResultId)).stream()
            .map(com.truthscope.web.entity.VerifySource::getUrl)
            .collect(Collectors.toSet());
    Set<String> newUrls =
        newResult.evidence().stream()
            .map(com.truthscope.web.scoring.EvidenceSnapshot::url)
            .collect(Collectors.toSet());

    // 4. SupersedeDecider — empty 면 confirmRecheck 후 return
    Optional<SupersedeReason> reason =
        SupersedeDecider.decide(
            oldScore, newScore, oldTier, newTier, oldLabel, newLabel, oldUrls, newUrls, policy);

    if (reason.isEmpty()) {
      old.confirmRecheck();
      verificationResultRepository.save(old); // dirty-checking 의존 대신 명시 저장 (PLAN 4번 "저장 후 return")
      return;
    }

    // 4a. 기존 행 supersede 마킹 후 flush (partial unique 위반 차단을 위해 saveAndFlush)
    old.markSuperseded(reason.get());
    verificationResultRepository.saveAndFlush(old);

    // 4b. 새 행 생성 — originalResultId 채우기
    UUID originalResultId =
        old.getOriginalResultId() != null ? old.getOriginalResultId() : old.getId();
    VerificationResult newEntity =
        VerificationResultFactory.buildResult(
            newSignal, old.getClaim(), newResult.evidence(), originalResultId);
    VerificationResult saved = verificationResultRepository.saveAndFlush(newEntity);

    // 4c. 기존 행에 supersededBy 연결
    old.linkSupersededBy(saved.getId());

    // 4d. 새 VerifySource 저장
    List<VerifySource> newSources = VerifySourceConverter.toEntities(saved, newResult.evidence());
    verifySourceRepository.saveAll(newSources);

    // 5. 세션 재집계 — article → session 체인은 2) 체인 로드에서 이미 JOIN FETCH 됨
    Article article = old.getClaim().getArticle();
    UUID articleId = article.getId();
    AnalysisSession session = article.getSession();

    List<Claim> allClaims = claimRepository.findByArticleIdOrderBySortOrderAsc(articleId);
    List<UUID> claimIds = allClaims.stream().map(Claim::getId).toList();
    List<VerificationResult> currentResults =
        verificationResultRepository.findCurrentByClaimIdIn(claimIds);

    List<ClaimVerificationSignal> signals =
        currentResults.stream()
            .map(r -> deriveSignalFromResult(r.getTier(), r.getScore(), r.getTier3Reason()))
            .toList();

    Optional<ArticleFactScore> newTotalScore =
        ArticleFactScoreAggregator.aggregateArticleFactScore(signals, articleScorePolicy);
    CoverageSummary newCoverage = CoverageAggregator.aggregateCoverage(signals);

    short tier1Count = (short) signals.stream().filter(s -> s.tier().equals((short) 1)).count();
    short tier2Count = (short) signals.stream().filter(s -> s.tier().equals((short) 2)).count();
    short tier3Count = (short) signals.stream().filter(s -> s.tier().equals((short) 3)).count();
    Short sessionTotalScore =
        newTotalScore.map(s -> (short) Math.min(100, Math.max(0, s.value()))).orElse(null);

    session.updateAggregates(sessionTotalScore, newCoverage, tier1Count, tier2Count, tier3Count);
    analysisSessionRepository.save(session);
  }

  // CHECKSTYLE:ON

  /**
   * VerificationResult 저장 필드로부터 ClaimVerificationSignal 을 역도출한다.
   *
   * <p>U6 단위 테스트 대상. score 있으면 SCORABLE, 없으면 tier3Reason 매핑 (null 이면 INSUFFICIENT 폴백).
   * transparency: tier 1 = EXPLICIT, tier 2 = AMBIGUOUS, tier 3 = NONE.
   *
   * @param tier 검증 tier (1/2/3)
   * @param score DB 저장 점수 (null 이면 비판정)
   * @param tier3Reason DB 저장 tier3 사유 (비판정일 때만 의미 있음)
   * @return 역도출된 ClaimVerificationSignal (claimId 는 UUID.randomUUID() 플레이스홀더)
   */
  static ClaimVerificationSignal deriveSignalFromResult(
      short tier, Short score, Tier3Reason tier3Reason) {
    ClaimScoreStatus status;
    Integer signalScore;
    if (score != null) {
      status = ClaimScoreStatus.SCORABLE;
      signalScore = (int) score;
    } else {
      status = mapTier3ReasonToStatus(tier3Reason);
      signalScore = null;
    }
    SourceTransparency transparency =
        tier == 1
            ? SourceTransparency.EXPLICIT
            : tier == 2 ? SourceTransparency.AMBIGUOUS : SourceTransparency.NONE;
    return new ClaimVerificationSignal(UUID.randomUUID(), tier, signalScore, status, transparency);
  }

  /**
   * Tier3Reason 을 ClaimScoreStatus 로 역매핑한다.
   *
   * <p>레거시 null+null(score null, tier3Reason null) 은 INSUFFICIENT 폴백.
   */
  private static ClaimScoreStatus mapTier3ReasonToStatus(Tier3Reason tier3Reason) {
    if (tier3Reason == null) {
      return ClaimScoreStatus.INSUFFICIENT; // 레거시 폴백
    }
    return switch (tier3Reason) {
      case INSUFFICIENT -> ClaimScoreStatus.INSUFFICIENT;
      case TIME_SENSITIVE -> ClaimScoreStatus.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> ClaimScoreStatus.OUT_OF_SCOPE;
    };
  }

  /**
   * null-safe 최신 시각 반환.
   *
   * @param a 첫 번째 시각 (null 가능)
   * @param b 두 번째 시각 (null 가능)
   * @return 둘 중 더 최신인 시각. 둘 다 null 이면 null.
   */
  private static LocalDateTime latestOf(LocalDateTime a, LocalDateTime b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }
}
