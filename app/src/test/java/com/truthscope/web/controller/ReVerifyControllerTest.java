package com.truthscope.web.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.config.SecurityConfig;
import com.truthscope.web.dto.response.ReVerifyAcceptedResponse;
import com.truthscope.web.exception.ConflictException;
import com.truthscope.web.exception.GlobalExceptionHandler;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.exception.TooManyRequestsException;
import com.truthscope.web.security.SupabaseAuthenticationEntryPoint;
import com.truthscope.web.service.ReVerifyService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ReVerifyController 단위 테스트 (WebMvcTest + Service mock) */
@WebMvcTest(controllers = ReVerifyController.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  SupabaseAuthenticationEntryPoint.class
})
@ActiveProfiles("test")
class ReVerifyControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ReVerifyService reVerifyService;

  // WebMvcTest 컨텍스트 로드용 — SecurityConfig 가 JwtDecoder bean 요구
  @MockitoBean private JwtDecoder jwtDecoder;

  // -----------------------------------------------------------------------
  // 정상 경로
  // -----------------------------------------------------------------------

  @Test
  @DisplayName(
      "POST /api/v1/verification-results/{id}/re-verify — 202 + body resultId/claimId/ACCEPTED")
  void reVerify_accepted_returns202WithBody() throws Exception {
    UUID resultId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    ReVerifyAcceptedResponse response =
        ReVerifyAcceptedResponse.builder()
            .resultId(resultId)
            .claimId(claimId)
            .status("ACCEPTED")
            .build();
    given(reVerifyService.accept(resultId)).willReturn(response);

    mockMvc
        .perform(post("/api/v1/verification-results/" + resultId + "/re-verify"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.resultId").value(resultId.toString()))
        .andExpect(jsonPath("$.claimId").value(claimId.toString()))
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
  }

  // -----------------------------------------------------------------------
  // 오류 경로 — GlobalExceptionHandler 경유
  // -----------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/v1/verification-results/{id}/re-verify — 결과 미존재 → 404")
  void reVerify_notFound_returns404() throws Exception {
    UUID resultId = UUID.randomUUID();
    given(reVerifyService.accept(resultId)).willThrow(new NotFoundException("검증 결과를 찾을 수 없습니다"));

    mockMvc
        .perform(post("/api/v1/verification-results/" + resultId + "/re-verify"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404));
  }

  @Test
  @DisplayName("POST /api/v1/verification-results/{id}/re-verify — 이미 supersede → 409")
  void reVerify_alreadySuperseded_returns409() throws Exception {
    UUID resultId = UUID.randomUUID();
    given(reVerifyService.accept(resultId)).willThrow(new ConflictException("이미 정정된 결과입니다"));

    mockMvc
        .perform(post("/api/v1/verification-results/" + resultId + "/re-verify"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.statusCode").value(409));
  }

  @Test
  @DisplayName("POST /api/v1/verification-results/{id}/re-verify — 쿨다운 미충족 → 429")
  void reVerify_cooldown_returns429() throws Exception {
    UUID resultId = UUID.randomUUID();
    given(reVerifyService.accept(resultId)).willThrow(new TooManyRequestsException("재검증 쿨다운 중입니다"));

    mockMvc
        .perform(post("/api/v1/verification-results/" + resultId + "/re-verify"))
        .andExpect(status().is(429))
        .andExpect(jsonPath("$.statusCode").value(429));
  }

  @Test
  @DisplayName("POST /api/v1/verification-results/notauuid/re-verify — invalid UUID → 400")
  void reVerify_invalidUuid_returns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/verification-results/notauuid/re-verify"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400));
  }
}
