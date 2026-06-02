package com.truthscope.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truthscope.web.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 인증 실패(토큰 부재/무효) 시 401 JSON 응답을 반환하는 EntryPoint. */
@Component
@RequiredArgsConstructor
public class SupabaseAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    res.setStatus(HttpStatus.UNAUTHORIZED.value());
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            objectMapper.writeValueAsString(
                ApiErrorResponse.builder()
                    .status("fail")
                    .statusCode(401)
                    .message("인증이 필요합니다")
                    .build()));
  }
}
