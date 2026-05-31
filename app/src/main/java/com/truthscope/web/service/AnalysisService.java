package com.truthscope.web.service;

import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.ExtractedArticle;
import jakarta.annotation.Nullable;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 뉴스 기사 분석 오케스트레이션 서비스 (동기 추출+영속화, 비동기 claims 이후 AsyncAnalysisService 위임) */
@Service
@RequiredArgsConstructor
public class AnalysisService {

  private final AnalysisTransactionService transactionService;
  private final ContentExtractService contentExtractService;
  private final AsyncAnalysisService asyncProcessor;

  /**
   * 뉴스 기사 URL을 받아 기사 추출과 영속화를 동기로 수행한 뒤 claims 이후를 비동기로 위임한다.
   *
   * <p>단계:
   *
   * <ol>
   *   <li>세션 생성 (트랜잭션)
   *   <li>기사 본문 추출 (트랜잭션 밖, 외부 HTTP Jsoup)
   *   <li>Article 저장 + 상태 EXTRACTING 전이 (트랜잭션, 즉시 커밋)
   *   <li>EXTRACTING 스냅샷 DTO 반환 + 비동기 프로세서에 claims 이후 위임
   * </ol>
   *
   * <p>동기 구간에서 RuntimeException 발생 시 세션을 FAILED로 전이한다. markFailed 자체 실패 시 원본 예외에 suppressed로 추가.
   */
  public AnalysisResponse analyze(AnalysisRequest request, @Nullable String userApiKey) {
    UUID sessionId = transactionService.createPendingSession();
    try {
      ExtractedArticle extracted = contentExtractService.extract(request.url());
      // persistArticleAndUpdateStatus는 독립 @Transactional이므로 메서드 반환 시 즉시 커밋.
      // 반환 AnalysisResponse는 AnalysisConverter.toResponse(session, article)로 빌드된 EXTRACTING 스냅샷
      // DTO.
      AnalysisResponse response =
          transactionService.persistArticleAndUpdateStatus(sessionId, request.url(), extracted);
      // 커밋 후 제출이므로 비동기 스레드가 미커밋 session/article을 못 보는 race가 없다.
      asyncProcessor.process(sessionId, response.getArticleId(), extracted.getBody(), userApiKey);
      return response; // EXTRACTING 스냅샷 (process가 인라인 실행돼 DB가 COMPLETED여도 이 DTO 문자열은 EXTRACTING
      // 고정).
    } catch (RuntimeException ex) {
      try {
        transactionService.markFailed(sessionId);
      } catch (RuntimeException markEx) {
        ex.addSuppressed(markEx);
      }
      throw ex;
    }
  }

  /**
   * Backward compat, userApiKey null 위임. (VerificationCascadeIntegrationTest:174,250가 호출, 제거 금지)
   */
  public AnalysisResponse analyze(AnalysisRequest request) {
    return analyze(request, null);
  }
}
