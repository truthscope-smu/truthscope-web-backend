package com.truthscope.web.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.config.SecurityConfig;
import com.truthscope.web.security.SupabaseAuthenticationEntryPoint;
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

/** GlobalExceptionHandler 단위 테스트 */
@WebMvcTest(controllers = ExceptionTestController.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  SupabaseAuthenticationEntryPoint.class
})
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  // SecurityConfig에 oauth2ResourceServer + JwtDecoder bean이 필요 — WebMvcTest 컨텍스트 로드용 mock
  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  @DisplayName("NotFoundException → 404, status=fail 반환")
  void notFoundReturns404() throws Exception {
    mockMvc
        .perform(get("/test/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(404))
        .andExpect(jsonPath("$.message").value("리소스를 찾을 수 없음"));
  }

  @Test
  @DisplayName("BadRequestException → 400, status=fail 반환")
  void badRequestReturns400() throws Exception {
    mockMvc
        .perform(get("/test/bad-request"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("잘못된 요청"));
  }

  @Test
  @DisplayName("처리되지 않은 예외 → 500, status=error, message=서버 내부 오류 반환")
  void unhandledExceptionReturns500() throws Exception {
    mockMvc
        .perform(get("/test/server-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.statusCode").value(500))
        .andExpect(jsonPath("$.message").value("서버 내부 오류"));
  }

  @Test
  @DisplayName("IllegalArgumentException → 400, status=fail 반환")
  void illegalArgumentReturns400() throws Exception {
    mockMvc
        .perform(get("/test/illegal-argument"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("잘못된 인자"));
  }

  @Test
  @DisplayName("IllegalStateException → 409, status=fail 반환")
  void illegalStateReturns409() throws Exception {
    mockMvc
        .perform(get("/test/illegal-state"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(409))
        .andExpect(jsonPath("$.message").value("잘못된 상태"));
  }

  @Test
  @DisplayName("MethodArgumentTypeMismatchException → 400, ApiErrorResponse shape")
  void typeMismatchReturns400() throws Exception {
    mockMvc
        .perform(get("/test/type-mismatch/notauuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(400));
  }

  @Test
  @DisplayName("HttpMessageNotReadableException → 400, ApiErrorResponse shape")
  void messageNotReadableReturns400() throws Exception {
    mockMvc
        .perform(
            post("/test/malformed-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("fail"))
        .andExpect(jsonPath("$.statusCode").value(400));
  }
}
