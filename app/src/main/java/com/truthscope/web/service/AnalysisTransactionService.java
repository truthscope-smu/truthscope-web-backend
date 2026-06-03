package com.truthscope.web.service;

import com.truthscope.web.converter.AnalysisConverter;
import com.truthscope.web.converter.VerifySourceConverter;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.Member;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import com.truthscope.web.entity.enums.ClaimImportance;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.entity.enums.Verdict;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.repository.VerifySourceRepository;
import com.truthscope.web.scoring.ArticleFactScore;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.CoverageSummary;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.SourceTransparencySummary;
import com.truthscope.web.scoring.TruthLabel;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 분석 세션/기사/claim/검증 결과 저장을 위한 트랜잭션 전용 서비스 — self-invocation 프록시 우회 방지 */
@Service
@RequiredArgsConstructor
public class AnalysisTransactionService {

  private final AnalysisSessionRepository sessionRepository;
  private final ArticleRepository articleRepository;
  private final ClaimRepository claimRepository;
  private final VerificationResultRepository verificationResultRepository;
  private final VerifySourceRepository verifySourceRepository;

  /**
   * 세션 생성 후 ID 반환.
   *
   * @param member 인증 사용자(null이면 익명 — member_id NULL)
   */
  @Transactional
  public UUID createPendingSession(Member member) {
    AnalysisSession session =
        AnalysisSession.builder()
            .member(member)
            .status(SessionStatus.PENDING)
            .requestedAt(LocalDateTime.now())
            .build();
    return sessionRepository.save(session).getId();
  }

  /** Article 저장 + 세션 상태 EXTRACTING으로 전이 */
  @Transactional
  public AnalysisResponse persistArticleAndUpdateStatus(
      UUID sessionId, String url, ExtractedArticle extracted) {
    AnalysisSession session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다"));

    Article article =
        Article.extract(
                url,
                extracted.getTitle(),
                extracted.getBody(),
                extracted.getLang(),
                extracted.getDomain())
            .attachTo(session);
    Article savedArticle = articleRepository.save(article);

    session.updateStatus(SessionStatus.EXTRACTING);

    return AnalysisConverter.toResponse(session, savedArticle);
  }

  /**
   * ClaimDraft 목록을 Claim entity로 변환하여 영속화한다.
   *
   * <p>Wave 2 µ2.4 신규. ClaimDraft → Claim entity 변환 + DB 저장. attributeSpeaker 정보(speakerName /
   * isQuotedClaim / originalContext)는 ClaimDraft에서 직접 매핑한다 (v1.x attribution pipeline 미적용).
   *
   * @param articleId 저장 대상 Article ID
   * @param drafts Wave 1 ClaimAnalysisPort 가 추출한 draft 목록
   * @return 저장된 Claim entity 목록 (draft 순서 보존)
   */
  @Transactional
  public List<Claim> persistClaims(UUID articleId, List<ClaimDraft> drafts) {
    Article article =
        articleRepository
            .findById(articleId)
            .orElseThrow(() -> new IllegalStateException("Article을 찾을 수 없습니다: " + articleId));

    List<Claim> savedClaims = new ArrayList<>(drafts.size());
    short sortOrder = 0;
    for (ClaimDraft draft : drafts) {
      // Claim PK는 JPA @GeneratedValue(UUID)로 자동 생성. ClaimDraft.claimId와 다르므로 cascade signal과
      // 매칭은 persistCascadeResults에서 인덱스 페어링(savedClaims.get(i))으로 처리.
      Claim claim =
          Claim.builder()
              .article(article)
              .text(draft.claimText())
              .importance(ClaimImportance.MEDIUM)
              .sortOrder(sortOrder++)
              .speakerName(draft.speakerName())
              .isQuotedClaim(draft.isQuotedClaim())
              .originalContext(draft.originalContext())
              .build();
      savedClaims.add(claimRepository.save(claim));
    }
    return savedClaims;
  }

  /**
   * Wave 2 Cascade 결과를 영속화하고 세션을 COMPLETED로 전이한다.
   *
   * <p>Wave 2 µ2.4 신규. Phase 66b T7 amend: cascadeResults 추가 + VerifySource 저장.
   *
   * <ul>
   *   <li>R1-8 amend: Integer(ClaimVerificationSignal.score) → Short(VerificationResult.score) 경계
   *       변환. Math.min(100, Math.max(0, score))로 클램프.
   *   <li>R2-6 amend: Tier 2 disclaimer 원문 고정 "AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요."
   *   <li>CX2-8 amend: coverage 항상 non-null CoverageSummary (검증 가능 claim 0건 시 모든 count=0).
   *   <li>VerificationTrace 영속화: µ2.5로 이연 (Gemini audit log 연동 필요, v1.x skeleton에서 호출 없음).
   * </ul>
   *
   * @param sessionId 대상 세션 ID
   * @param savedClaims persistClaims가 반환한 영속화된 Claim 엔티티 목록 (cascadeResults와 인덱스 페어링)
   * @param totalScore Phase 55 ArticleFactScoreAggregator 결과 (검증 가능 claim 없으면 empty)
   * @param articleLabel Phase 55 TruthLabelDeriver 결과 (totalScore empty 이면 empty)
   * @param transparencySummary Phase 55 SourceTransparencyAggregator 결과
   * @param coverage Phase 55 CoverageAggregator 결과 (non-null, 빈 경우 모든 count=0)
   * @param cascadeResults Wave 2 cascade 결과 (savedClaims 와 동일 인덱스 순서; signal() 로 신호 도출)
   */
  @Transactional
  public void persistCascadeResults(
      UUID sessionId,
      List<Claim> savedClaims,
      Optional<ArticleFactScore> totalScore,
      Optional<TruthLabel> articleLabel,
      SourceTransparencySummary transparencySummary,
      CoverageSummary coverage,
      List<ClaimCascadeResult> cascadeResults) {

    // signals는 cascadeResults에서 도출 (cascadeResults.get(i).signal() == 이전 signals.get(i))
    List<ClaimVerificationSignal> signals =
        cascadeResults.stream().map(ClaimCascadeResult::signal).toList();

    if (signals.size() != savedClaims.size()) {
      throw new IllegalStateException(
          "signals와 savedClaims 크기 불일치: signals="
              + signals.size()
              + ", savedClaims="
              + savedClaims.size());
    }

    AnalysisSession session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalStateException("세션을 찾을 수 없습니다: " + sessionId));

    // 1. signals → VerificationResult entity 영속화 + VerifySource 저장 (인덱스 페어링)
    for (int i = 0; i < signals.size(); i++) {
      List<EvidenceSnapshot> evidence = cascadeResults.get(i).evidence();
      VerificationResult saved =
          verificationResultRepository.save(
              buildResult(signals.get(i), savedClaims.get(i), evidence));
      List<VerifySource> rows = VerifySourceConverter.toEntities(saved, evidence);
      verifySourceRepository.saveAll(rows);
    }

    // 2. Tier count 계산 (Integer → Short 경계)
    short tier1Count = (short) signals.stream().filter(s -> s.tier() == 1).count();
    short tier2Count = (short) signals.stream().filter(s -> s.tier() == 2).count();
    short tier3Count = (short) signals.stream().filter(s -> s.tier() == 3).count();

    // 3. ArticleFactScore(int value) → Short 변환
    Short sessionTotalScore =
        totalScore.map(s -> (short) Math.min(100, Math.max(0, s.value()))).orElse(null);

    // 4. AnalysisSession 비즈니스 메서드로 집계 필드 갱신 + COMPLETED 전이
    // NOTE: articleLabel 은 현재 AnalysisSession 스키마에 컬럼 없음 — Phase 55 후속 스키마 확장 시 추가 예정
    //       (V3 마이그레이션 트랙 소관). 현재는 totalScore/coverage/tier counts만 영속화.
    session.completeCascade(sessionTotalScore, coverage, tier1Count, tier2Count, tier3Count);
    sessionRepository.save(session);

    // NOTE: VerificationTrace 영속화는 µ2.5로 이연.
    //       이유: VerificationTrace 14컬럼(V5 11 + V6 3)은 Gemini audit log(prompt/response raw)를 포함하며
    //       v1.x skeleton cascade에서 Gemini 직접 호출이 없어 채울 수 없음.
    //       µ2.5 통합 테스트 단계에서 HybridCascadeService + Gemini 연동 후 구현.
  }

  /** 세션 상태를 ANALYZING으로 전이 (비동기 분석 시작 시점). */
  @Transactional
  public void markAnalyzing(UUID sessionId) {
    sessionRepository
        .findById(sessionId)
        .ifPresent(session -> session.updateStatus(SessionStatus.ANALYZING));
  }

  /** 세션 상태를 FAILED로 전이 */
  @Transactional
  public void markFailed(UUID sessionId) {
    sessionRepository
        .findById(sessionId)
        .ifPresent(session -> session.updateStatus(SessionStatus.FAILED));
  }

  /**
   * ClaimScoreStatus를 Tier3Reason으로 매핑한다.
   *
   * <p>SCORABLE(Tier 1/2)은 tier3_reason = NULL (V6 CHECK 정합).
   *
   * @param status ClaimVerificationSignal 의 status
   * @return Tier3Reason 또는 null (SCORABLE)
   */
  private Tier3Reason mapTier3Reason(ClaimScoreStatus status) {
    return switch (status) {
      case INSUFFICIENT -> Tier3Reason.INSUFFICIENT;
      case TIME_SENSITIVE -> Tier3Reason.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> Tier3Reason.OUT_OF_SCOPE;
      case SCORABLE -> null;
    };
  }

  /**
   * status와 evidence stance를 Verdict로 매핑한다. SCORABLE은 evidence 다수결: 반박이 뒷받침보다 많으면 CONTRADICTED, 그
   * 외(동률·evidence 없음 포함)는 SUPPORTED. verdict 컬럼 NOT NULL이라 모든 status에 값을 반환한다.
   */
  private Verdict mapVerdict(ClaimScoreStatus status, List<EvidenceSnapshot> evidence) {
    return switch (status) {
      case SCORABLE -> isMajorityContradicted(evidence) ? Verdict.CONTRADICTED : Verdict.SUPPORTED;
      case INSUFFICIENT -> Verdict.INSUFFICIENT;
      case TIME_SENSITIVE -> Verdict.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> Verdict.OUT_OF_SCOPE;
    };
  }

  /** evidence stance 다수결 — CONTRADICTED 수가 SUPPORTED 수보다 많으면 true (null/빈 리스트는 false). */
  static boolean isMajorityContradicted(List<EvidenceSnapshot> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return false;
    }
    long contradicted = evidence.stream().filter(e -> "CONTRADICTED".equals(e.stance())).count();
    long supported = evidence.stream().filter(e -> "SUPPORTED".equals(e.stance())).count();
    return contradicted > supported;
  }

  /**
   * 단일 ClaimVerificationSignal을 VerificationResult entity로 변환한다 (R1-8 score 변환 + R2-6 disclaimer +
   * verdict/tier3Reason/reason 매핑 포함).
   */
  private VerificationResult buildResult(
      ClaimVerificationSignal signal, Claim claim, List<EvidenceSnapshot> evidence) {
    Short shortScore =
        signal.score() == null ? null : (short) Math.min(100, Math.max(0, signal.score()));
    String disclaimer = signal.tier() == 2 ? "AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요." : null;
    return VerificationResult.builder()
        .claim(claim)
        .tier(signal.tier())
        .score(shortScore)
        .verdict(mapVerdict(signal.status(), evidence))
        .tier3Reason(mapTier3Reason(signal.status()))
        .reason(buildReason(signal))
        .disclaimer(disclaimer)
        .verifiedAt(LocalDateTime.now())
        .build();
  }

  /**
   * VerificationResult.reason (NOT NULL TEXT) 의 v1.x 기본 메시지를 생성한다.
   *
   * <p>Tier 1: 팩트체크 기관 매칭 / Tier 2: 다중 출처 cascade / Tier 3: Validator 미판정 사유. µ2.5 이후 cascade trace
   * 메타데이터를 활용해 정밀 사유로 교체 예정.
   */
  private String buildReason(ClaimVerificationSignal signal) {
    return switch (signal.status()) {
      case SCORABLE -> signal.tier() == 1 ? "Tier 1 팩트체크 기관 매칭 결과" : "Tier 2 다중 출처 cascade 검증 결과";
      case INSUFFICIENT -> "Tier 3 검증 출처 부족";
      case TIME_SENSITIVE -> "Tier 3 시점 의존성으로 검증 보류";
      case OUT_OF_SCOPE -> "Tier 3 검증 범위 외 claim";
    };
  }
}
