package com.truthscope.web.controller;

import com.truthscope.web.dto.AuthenticatedUser;
import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.AnalysisSessionHistoryResponse;
import com.truthscope.web.exception.UnauthorizedException;
import com.truthscope.web.service.AnalysisHistoryService;
import com.truthscope.web.service.AnalysisService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final AnalysisHistoryService analysisHistoryService;

  /**
   * 뉴스 기사 분석 요청. 익명(토큰 없음) 및 인증(토큰 있음) 모두 허용(permitAll). 유효 Bearer 첨부 시 member_id 바인딩.
   *
   * <p>BE #74 amend: BYOK 사용자 키를 {@code X-User-Gemini-Key} 헤더로 1회성 수신 (ADR-004 §c). 헤더 부재 시 서버 기본 키
   * 사용.
   *
   * @param request 분석할 뉴스 기사 URL
   * @param userApiKey BYOK 사용자 Gemini API 키 (헤더 옵션, 없으면 서버 기본 키)
   * @param jwt 인증 JWT (익명이면 null)
   * @return 생성된 세션 ID와 상태 (201 Created)
   */
  @PostMapping
  public ResponseEntity<AnalysisResponse> analyze(
      @Valid @RequestBody AnalysisRequest request,
      @RequestHeader(name = "X-User-Gemini-Key", required = false) String userApiKey,
      @AuthenticationPrincipal Jwt jwt) {
    AuthenticatedUser user = toUser(jwt);
    AnalysisResponse response = analysisService.analyze(request, userApiKey, user);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 인증 사용자 분석 이력 조회. GET /api/v1/analysis-sessions — SecurityConfig에서 authenticated() 필수.
   *
   * @param jwt 인증 JWT (SecurityConfig에서 authenticated 보장으로 non-null)
   * @return 이력 목록 (requested_at DESC)
   */
  @GetMapping
  public List<AnalysisSessionHistoryResponse> getMySessions(@AuthenticationPrincipal Jwt jwt) {
    return analysisHistoryService.findMySessions(requireUserId(jwt));
  }

  /**
   * JWT subject를 UUID로 파싱한다. sub가 UUID 형식이 아니면 UnauthorizedException(401)을 던진다.
   *
   * @param jwt 인증 JWT (null 불가)
   * @return 파싱된 UUID
   */
  private static UUID requireUserId(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new UnauthorizedException("유효하지 않은 토큰 subject입니다");
    }
  }

  /**
   * JWT가 있으면 AuthenticatedUser를 생성하고, null이면 null을 반환한다.
   *
   * @param jwt 인증 JWT (익명이면 null)
   * @return AuthenticatedUser 또는 null
   */
  private static AuthenticatedUser toUser(Jwt jwt) {
    if (jwt == null) {
      return null;
    }
    return new AuthenticatedUser(requireUserId(jwt), jwt.getClaimAsString("email"));
  }
}
