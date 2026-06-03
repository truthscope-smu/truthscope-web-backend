package com.truthscope.web.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.scoring.ArticleScorePolicy;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.service.verification.VerificationCascadeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("AsyncAnalysisService unit")
class AsyncAnalysisServiceTest {

  private AnalysisTransactionService transactionService;
  private ClaimAnalysisPort claimAnalysisPort;
  private VerificationCascadeService verificationCascadeService;
  private ArticleScorePolicy scorePolicy;
  private ScoreBandPolicy bandPolicy;

  private AsyncAnalysisService asyncAnalysisService;

  @BeforeEach
  void setUp() {
    transactionService = mock(AnalysisTransactionService.class);
    claimAnalysisPort = mock(ClaimAnalysisPort.class);
    verificationCascadeService = mock(VerificationCascadeService.class);
    scorePolicy = mock(ArticleScorePolicy.class);
    bandPolicy = mock(ScoreBandPolicy.class);

    asyncAnalysisService =
        new AsyncAnalysisService(
            transactionService,
            claimAnalysisPort,
            verificationCascadeService,
            scorePolicy,
            bandPolicy);
  }

  @Test
  @DisplayName(
      "happy path: markAnalyzing, analyze, persistClaims, cascade, persistCascadeResults 순서대로 호출")
  void happyPath() {
    UUID sessionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    String body = "기사 본문";

    // 빈 cascade 경로: SCORABLE signal 0건이라 집계 4함수가 policy를 건드리지 않고 안전하게 종료(persist까지 도달).
    when(claimAnalysisPort.analyze(body, null)).thenReturn(List.of());
    when(transactionService.persistClaims(eq(articleId), any())).thenReturn(List.of());
    when(verificationCascadeService.cascade(any(), any())).thenReturn(List.of());

    asyncAnalysisService.process(sessionId, articleId, body, null, null);

    InOrder order = inOrder(transactionService, claimAnalysisPort, verificationCascadeService);
    order.verify(transactionService).markAnalyzing(sessionId);
    order.verify(claimAnalysisPort).analyze(body, null);
    order.verify(transactionService).persistClaims(eq(articleId), any());
    order.verify(verificationCascadeService).cascade(any(), any());
    order
        .verify(transactionService)
        .persistCascadeResults(eq(sessionId), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("회귀 sim E: claimAnalysisPort.analyze 예외 시 markFailed(sessionId) 1회 호출")
  void analyzeThrowCallsMarkFailed() {
    UUID sessionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    String body = "기사 본문";

    doThrow(new RuntimeException("Gemini 호출 실패")).when(claimAnalysisPort).analyze(body, null);

    asyncAnalysisService.process(sessionId, articleId, body, null, null);

    verify(transactionService, times(1)).markFailed(sessionId);
  }
}
