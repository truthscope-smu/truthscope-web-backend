package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.ExtractedArticle;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 71 B1 단위 테스트 — 추출 실패 시 세션/member 미생성.
 *
 * <p>핵심 단언: contentExtractService.extract 가 RuntimeException을 던질 때 createPendingSession 및
 * memberService.upsert 가 호출되지 않음을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalysisService — Phase 71 B1 추출 선행 + 세션 미생성")
class AnalysisServiceTest {

  @Mock private AnalysisTransactionService transactionService;

  @Mock private ContentExtractService contentExtractService;

  @Mock private AsyncAnalysisService asyncProcessor;

  @Mock private MemberService memberService;

  @InjectMocks private AnalysisService analysisService;

  /**
   * B1 핵심 시나리오: 추출 실패 시 세션/member 미생성.
   *
   * <p>contentExtractService.extract 가 RuntimeException을 던지면 — createPendingSession 과
   * memberService.upsert 는 절대 호출되지 않아야 한다.
   */
  @Test
  @DisplayName("추출 실패(RuntimeException) 시 세션 미생성 + member upsert 미호출")
  void extractFails_noSessionCreated() {
    // Given
    AnalysisRequest request = new AnalysisRequest("https://example.com/article");
    when(contentExtractService.extract(anyString())).thenThrow(new RuntimeException("연결 타임아웃"));

    // When + Then: 예외 전파 확인
    assertThatThrownBy(() -> analysisService.analyze(request, null, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("연결 타임아웃");

    // 세션 생성 및 member upsert 가 호출되지 않아야 함 (B1 핵심 단언)
    verify(transactionService, never()).createPendingSession(any());
    verify(memberService, never()).upsert(any(), any());
  }

  /**
   * 정상 흐름: 추출 성공 시 세션 생성 1회 + persistArticleAndUpdateStatus 호출.
   *
   * <p>extract 정상 반환 → createPendingSession 1회 호출됨을 검증한다.
   */
  @Test
  @DisplayName("추출 성공(happy path) 시 세션 생성 1회 호출")
  void happyPath_createsSession() {
    // Given
    AnalysisRequest request = new AnalysisRequest("https://example.com/article");
    UUID sessionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();

    ExtractedArticle stubArticle =
        ExtractedArticle.builder()
            .title("테스트 기사 제목")
            .body("테스트 기사 본문")
            .lang("ko")
            .domain("example.com")
            .build();

    AnalysisResponse stubResponse =
        AnalysisResponse.builder()
            .sessionId(sessionId)
            .articleId(articleId)
            .status("EXTRACTING")
            .build();

    when(contentExtractService.extract(anyString())).thenReturn(stubArticle);
    when(transactionService.createPendingSession(any())).thenReturn(sessionId);
    when(transactionService.persistArticleAndUpdateStatus(any(), anyString(), any()))
        .thenReturn(stubResponse);

    // When
    AnalysisResponse response = analysisService.analyze(request, null, null);

    // Then: createPendingSession 1회 호출됨
    verify(transactionService).createPendingSession(null); // user=null → member=null
    verify(transactionService).persistArticleAndUpdateStatus(sessionId, request.url(), stubArticle);
  }
}
