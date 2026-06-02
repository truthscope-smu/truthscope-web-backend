package com.truthscope.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.config.SecurityConfig;
import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.dto.response.AnalysisSessionHistoryResponse;
import com.truthscope.web.exception.GlobalExceptionHandler;
import com.truthscope.web.security.SupabaseAuthenticationEntryPoint;
import com.truthscope.web.service.AnalysisHistoryService;
import com.truthscope.web.service.AnalysisService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** NewsController 단위 테스트 */
@WebMvcTest(controllers = NewsController.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  SupabaseAuthenticationEntryPoint.class
})
@ActiveProfiles("test")
class NewsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AnalysisService analysisService;

  @MockitoBean private AnalysisHistoryService analysisHistoryService;

  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setupJwtDecoder() {
    // 무효 토큰 패턴 stub: "Bearer invalid.token.here" 헤더가 들어오면 JwtDecoder가 BadJwtException throw
    given(jwtDecoder.decode("invalid.token.here")).willThrow(new BadJwtException("invalid token"));
  }

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — 익명(토큰 없음) → 201 반환 (permitAll 보장)")
  void analyze_anonymous_returns201() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    AnalysisResponse response =
        AnalysisResponse.builder()
            .sessionId(sessionId)
            .articleId(articleId)
            .status("EXTRACTING")
            .build();

    given(analysisService.analyze(any(AnalysisRequest.class), any(), any())).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/analysis-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://news.naver.com/article/001\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
        .andExpect(jsonPath("$.status").value("EXTRACTING"));
  }

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — 유효 JWT → 201 반환")
  void analyze_withValidJwt_returns201() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    AnalysisResponse response =
        AnalysisResponse.builder()
            .sessionId(sessionId)
            .articleId(UUID.randomUUID())
            .status("EXTRACTING")
            .build();

    given(analysisService.analyze(any(AnalysisRequest.class), any(), any())).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/analysis-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://news.naver.com/article/001\"}")
                .with(jwt().jwt(j -> j.subject(memberId.toString()).claim("email", "u@test.com"))))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — 무효 Bearer(잘못된 토큰) → 401 반환 (CX-1)")
  void analyze_invalidBearer_returns401() throws Exception {
    // @MockitoBean JwtDecoder가 있으면 SecurityConfig의 oauth2ResourceServer가 실제 JWKS fetch 없이
    // 동작한다. 무효 Bearer 헤더는 SecurityConfig가 401로 거부한다.
    mockMvc
        .perform(
            post("/api/v1/analysis-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://news.naver.com/article/001\"}")
                .header("Authorization", "Bearer invalid.token.here"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — URL 미입력 → 400 반환")
  void analyze_blankUrl_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/analysis-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — url 필드 누락 → 400 반환")
  void analyze_missingUrl_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/analysis-sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /api/v1/analysis-sessions — 토큰 없음 → 401 + ApiErrorResponse JSON (status:fail)")
  void getMySessions_noToken_returns401WithApiErrorResponse() throws Exception {
    mockMvc
        .perform(get("/api/v1/analysis-sessions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(401));
  }

  @Test
  @DisplayName("GET /api/v1/analysis-sessions — 유효 JWT → 200 + 목록 반환")
  void getMySessions_withJwt_returns200() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    AnalysisSessionHistoryResponse item =
        AnalysisSessionHistoryResponse.builder().sessionId(sessionId).status("COMPLETED").build();
    given(analysisHistoryService.findMySessions(memberId)).willReturn(List.of(item));

    mockMvc
        .perform(
            get("/api/v1/analysis-sessions")
                .with(jwt().jwt(j -> j.subject(memberId.toString()).claim("email", "u@test.com"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sessionId").value(sessionId.toString()));
  }

  @Test
  @DisplayName("GET /api/v1/analysis-sessions — 비-UUID subject JWT → 401 반환")
  void getMySessions_nonUuidSubject_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/analysis-sessions").with(jwt().jwt(j -> j.subject("not-a-uuid"))))
        .andExpect(status().isUnauthorized());
  }
}
