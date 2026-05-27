package com.truthscope.web.controller;

import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.service.AnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 뉴스 기사 분석 컨트롤러 */
@RestController
@RequestMapping("/api/v1/analysis-sessions")
@RequiredArgsConstructor
public class NewsController {

  private final AnalysisService analysisService;

  /**
   * 뉴스 기사 분석 요청.
   *
   * <p>BE #74 amend: BYOK 사용자 키를 {@code X-User-Gemini-Key} 헤더로 1회성 수신 (ADR-004 §c). 헤더 부재 시 서버 기본 키
   * 사용.
   *
   * @param request 분석할 뉴스 기사 URL
   * @param userApiKey BYOK 사용자 Gemini API 키 (헤더 옵션, 없으면 서버 기본 키)
   * @return 생성된 세션 ID와 상태 (201 Created)
   */
  @PostMapping
  public ResponseEntity<AnalysisResponse> analyze(
      @Valid @RequestBody AnalysisRequest request,
      @RequestHeader(name = "X-User-Gemini-Key", required = false) String userApiKey) {
    AnalysisResponse response = analysisService.analyze(request, userApiKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
