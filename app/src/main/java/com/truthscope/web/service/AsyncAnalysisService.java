package com.truthscope.web.service;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.scoring.ArticleFactScore;
import com.truthscope.web.scoring.ArticleFactScoreAggregator;
import com.truthscope.web.scoring.ArticleScorePolicy;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimCascadeResult;
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
import jakarta.annotation.Nullable;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 분석 파이프라인 후반부(claims 이후)를 비동기로 실행하는 서비스. self-invocation 우회를 위해 별도 빈으로 분리. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAnalysisService {

  private final AnalysisTransactionService transactionService;
  private final ClaimAnalysisPort claimAnalysisPort;
  private final VerificationCascadeService verificationCascadeService;
  private final ArticleScorePolicy scorePolicy;
  private final ScoreBandPolicy bandPolicy;

  /**
   * 비동기 분석 후반부: markAnalyzing, claims, cascade, 집계, persist(COMPLETED).
   *
   * <p>@Async void라 예외 전파 불가 - 내부 try/catch에서 markFailed. markFailed 자체 실패도 중첩 try/catch로 로깅.
   *
   * @param publishedAt 기사 발행일 (nullable). Tier 2 evidence 윈도우의 기준일 폴백으로 cascade 에 전달된다. claimText 에
   *     날짜가 없을 때 발행 시점의 data.go.kr 원문을 검색하기 위함 (today 폴백 시 과거 기사가 매칭 0건이 되는 문제 회피).
   */
  @Async("analysisExecutor")
  public void process(
      UUID sessionId,
      UUID articleId,
      String body,
      @Nullable LocalDate publishedAt,
      @Nullable String userApiKey) {
    try {
      transactionService.markAnalyzing(sessionId);
      List<ClaimDraft> drafts = claimAnalysisPort.analyze(body, userApiKey);
      List<Claim> savedClaims = transactionService.persistClaims(articleId, drafts);
      List<ClaimCascadeResult> results = verificationCascadeService.cascade(drafts, publishedAt);
      // signals는 집계 4함수에만 사용. persistCascadeResults는 cascadeResults에서 signals를 자체 재도출.
      List<ClaimVerificationSignal> signals =
          results.stream().map(ClaimCascadeResult::signal).toList();
      Optional<ArticleFactScore> totalScore =
          ArticleFactScoreAggregator.aggregateArticleFactScore(signals, scorePolicy);
      Optional<TruthLabel> articleLabel =
          totalScore.map(s -> TruthLabelDeriver.deriveTruthLabel(s.value(), bandPolicy));
      SourceTransparencySummary transparency =
          SourceTransparencyAggregator.aggregateSourceTransparency(signals);
      CoverageSummary coverage = CoverageAggregator.aggregateCoverage(signals);
      transactionService.persistCascadeResults(
          sessionId, savedClaims, totalScore, articleLabel, transparency, coverage, results);
    } catch (RuntimeException ex) {
      log.error("[AsyncAnalysisService] sessionId={} 비동기 분석 실패", sessionId, ex);
      try {
        transactionService.markFailed(sessionId);
      } catch (RuntimeException markEx) {
        log.error(
            "[AsyncAnalysisService] sessionId={} markFailed 실패, 세션 상태 미수렴", sessionId, markEx);
      }
    }
  }
}
