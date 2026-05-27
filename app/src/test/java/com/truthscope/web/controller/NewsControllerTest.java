package com.truthscope.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.config.SecurityConfig;
import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.AnalysisResponse;
import com.truthscope.web.exception.GlobalExceptionHandler;
import com.truthscope.web.service.AnalysisService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** NewsController 단위 테스트 */
@WebMvcTest(controllers = NewsController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@ActiveProfiles("test")
class NewsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AnalysisService analysisService;

  @Test
  @DisplayName("POST /api/v1/analysis-sessions — 유효한 URL → 201 반환 (sessionId + articleId + status)")
  void analyze_validUrl_returns201() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    AnalysisResponse response =
        AnalysisResponse.builder()
            .sessionId(sessionId)
            .articleId(articleId)
            .status("EXTRACTING")
            .build();

    given(analysisService.analyze(any(AnalysisRequest.class), any())).willReturn(response);

    mockMvc
        .perform(
            post("/api/v1/analysis-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://news.naver.com/article/001\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
        .andExpect(jsonPath("$.articleId").value(articleId.toString()))
        .andExpect(jsonPath("$.status").value("EXTRACTING"));
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
}
