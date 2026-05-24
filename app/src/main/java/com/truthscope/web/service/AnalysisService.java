package com.truthscope.web.service;

import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.scoring.ArticleFactScore;
import com.truthscope.web.scoring.ArticleFactScoreAggregator;
import com.truthscope.web.scoring.ArticleScorePolicy;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.CoverageAggregator;
import com.truthscope.web.scoring.CoverageSummary;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.scoring.SourceTransparencyAggregator;
import com.truthscope.web.scoring.SourceTransparencySummary;
import com.truthscope.web.scoring.TruthLabel;
import com.truthscope.web.scoring.TruthLabelDeriver;
import com.truthscope.web.service.verification.VerificationCascadeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 뉴스 기사 분석 오케스트레이션 서비스 (Wave 2 cascade + Phase 55 4함수 통합) */
@Service
@RequiredArgsConstructor
public class AnalysisService {

  private final AnalysisTransactionService transactionService;
  private final ContentExtractService contentExtractService;
  // Wave 1: ClaimAnalysisPort — production = ClaimAnalysisService, stub = ClaimAnalysisStubService
  private final ClaimAnalysisPort claimAnalysisPort;
  // Wave 2: 3-Tier Cascade orchestrator
  private final VerificationCascadeService verificationCascadeService;
  // Phase 55 policy beans (4 집계 함수는 static — policy만 DI)
  private final ArticleScorePolicy scorePolicy;
  private final ScoreBandPolicy bandPolicy;

  /**
   * 뉴스 기사 URL을 받아 전체 분석 파이프라인을 실행한다.
   *
   * <p>단계:
   *
   * <ol>
   *   <li>세션 생성 (트랜잭션)
   *   <li>기사 본문 추출 (트랜잭션 밖 — 외부 HTTP Jsoup)
   *   <li>Article 저장 + 상태 EXTRACTING 전이 (트랜잭션)
   *   <li>Claim 추출 — ClaimAnalysisPort (Gemini 또는 stub)
   *   <li>Claim DB 영속화 (트랜잭션)
   *   <li>Wave 2 Cascade 검증 — VerificationCascadeService (외부 HTTP, 트랜잭션 밖)
   *   <li>Phase 55 집계 4함수 STATIC 직접 호출 (순수 함수, 트랜잭션 불필요)
   *   <li>Cascade 결과 영속화 + 세션 COMPLETED 전이 (트랜잭션)
   * </ol>
   *
   * <p>어느 단계에서든 RuntimeException 발생 시 세션을 FAILED로 전이한다. markFailed 자체 실패 시 원본 예외에 suppressed로 추가.
   */
  public AnalysisResponse analyze(AnalysisRequest request) {
    UUID sessionId = transactionService.createPendingSession();

    try {
      // 기사 본문 추출 (트랜잭션 밖 — 외부 HTTP)
      ExtractedArticle extracted = contentExtractService.extract(request.url());

      // Article 저장 + 세션 EXTRACTING 전이 (트랜잭션)
      // R3-3 amend: getArticleId() — AnalysisResponse 는 Lombok @Getter 클래스 (record 아님)
      UUID articleId =
          transactionService
              .persistArticleAndUpdateStatus(sessionId, request.url(), extracted)
              .getArticleId();

      // Wave 1: ClaimAnalysisPort — Gemini 또는 stub (auto-profile)
      List<ClaimDraft> drafts = claimAnalysisPort.analyze(extracted.getBody());

      // Wave 2 step 1: ClaimDraft → Claim entity 영속화 (트랜잭션)
      transactionService.persistClaims(articleId, drafts);

      // Wave 2 step 2: 3-Tier Cascade 검증 (외부 HTTP, 트랜잭션 밖)
      List<ClaimVerificationSignal> signals = verificationCascadeService.cascade(drafts);

      // CX2-1 Critical lock: Phase 55 집계 4함수 STATIC 직접 호출
      Optional<ArticleFactScore> totalScore =
          ArticleFactScoreAggregator.aggregateArticleFactScore(signals, scorePolicy);
      Optional<TruthLabel> articleLabel =
          totalScore.map(s -> TruthLabelDeriver.deriveTruthLabel(s.value(), bandPolicy));
      SourceTransparencySummary transparencySummary =
          SourceTransparencyAggregator.aggregateSourceTransparency(signals);
      CoverageSummary coverage = CoverageAggregator.aggregateCoverage(signals);

      // Wave 2 step 3: Cascade 결과 영속화 + 세션 COMPLETED 전이 (트랜잭션)
      transactionService.persistCascadeResults(
          sessionId, signals, totalScore, articleLabel, transparencySummary, coverage);

      // AnalysisResponse 빌드 — CX4-5/R4-5 amend: baseline 3 필드(sessionId/articleId/status) 유지
      return AnalysisResponse.builder()
          .sessionId(sessionId)
          .articleId(articleId)
          .status(SessionStatus.COMPLETED.name())
          .build();

    } catch (RuntimeException ex) {
      // 실패 시 세션 상태 FAILED 전이 (markFailed 실패 시 원본 예외에 suppressed 추가)
      try {
        transactionService.markFailed(sessionId);
      } catch (RuntimeException markEx) {
        ex.addSuppressed(markEx);
      }
      throw ex;
    }
  }
}
