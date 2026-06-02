package com.truthscope.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.config.SecurityConfig;
import com.truthscope.web.dto.response.ArticleResponse;
import com.truthscope.web.exception.ConflictException;
import com.truthscope.web.exception.GlobalExceptionHandler;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.security.SupabaseAuthenticationEntryPoint;
import com.truthscope.web.service.ArticleService;
import com.truthscope.web.service.ArticleVerificationService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ArticleController 단위 테스트 (WebMvcTest + Service mock) */
@WebMvcTest(controllers = ArticleController.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  SupabaseAuthenticationEntryPoint.class
})
@ActiveProfiles("test")
class ArticleControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ArticleService articleService;

  // findVerification 엔드포인트 추가로 컨트롤러 의존 주입됨 — WebMvcTest 컨텍스트 로드용 mock
  @MockitoBean private ArticleVerificationService articleVerificationService;

  // SecurityConfig에 oauth2ResourceServer + JwtDecoder bean이 필요 — WebMvcTest 컨텍스트 로드용 mock
  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  @DisplayName("GET /api/v1/articles/{id} — 200 + ArticleResponse 반환")
  void findById_returns200() throws Exception {
    UUID articleId = UUID.randomUUID();
    ArticleResponse response =
        ArticleResponse.builder()
            .id(articleId)
            .url("https://example.com/news/1")
            .title("제목")
            .body("본문")
            .lang("ko")
            .domain("example.com")
            .status("EXTRACTED")
            .sessionId(null)
            .extractedAt(LocalDateTime.now())
            .createdAt(LocalDateTime.now())
            .build();
    given(articleService.findById(articleId)).willReturn(response);

    mockMvc
        .perform(get("/api/v1/articles/" + articleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(articleId.toString()))
        .andExpect(jsonPath("$.url").value("https://example.com/news/1"))
        .andExpect(jsonPath("$.status").value("EXTRACTED"))
        .andExpect(jsonPath("$.sessionId").value(nullValue()));
  }

  @Test
  @DisplayName("GET /api/v1/articles/{id} — 미존재 → 404")
  void findById_notFound_returns404() throws Exception {
    UUID articleId = UUID.randomUUID();
    given(articleService.findById(articleId))
        .willThrow(new NotFoundException("Article not found: " + articleId));

    mockMvc
        .perform(get("/api/v1/articles/" + articleId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404));
  }

  @Test
  @DisplayName("GET /api/v1/articles/{id} — invalid UUID PathVariable → 400")
  void findById_invalidUuidPathVariable_returns400() throws Exception {
    mockMvc
        .perform(get("/api/v1/articles/notauuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.status").value("fail"));
  }

  @Test
  @DisplayName("POST /api/v1/articles/{id}/attach — 200 + status=ATTACHED 반환")
  void attachToSession_returns200WithAttached() throws Exception {
    UUID articleId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    ArticleResponse response =
        ArticleResponse.builder()
            .id(articleId)
            .url("https://example.com/news/2")
            .status("ATTACHED")
            .sessionId(sessionId)
            .build();
    given(articleService.attachToSession(any(UUID.class), any(UUID.class))).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/articles/" + articleId + "/attach")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ATTACHED"))
        .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
  }

  @Test
  @DisplayName("POST /api/v1/articles/{id}/attach — 재부착 → 409")
  void attachToSession_alreadyAttached_returns409() throws Exception {
    UUID articleId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    given(articleService.attachToSession(any(UUID.class), any(UUID.class)))
        .willThrow(new ConflictException("Article은 이미 세션에 부착되었습니다"));

    mockMvc
        .perform(
            post("/api/v1/articles/" + articleId + "/attach")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.statusCode").value(409));
  }

  @Test
  @DisplayName("POST /api/v1/articles/{id}/attach — sessionId null → 400")
  void attachToSession_missingSessionId_returns400() throws Exception {
    UUID articleId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/articles/" + articleId + "/attach")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400));
  }

  @Test
  @DisplayName("POST /api/v1/articles/{id}/attach — malformed UUID body → 400")
  void attachToSession_malformedUuidBody_returns400() throws Exception {
    UUID articleId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/articles/" + articleId + "/attach")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"notauuid\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400));
  }
}
