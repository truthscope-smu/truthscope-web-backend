package com.truthscope.web.exception;

/** 인증 실패 예외 — 401 상태 코드 */
public class UnauthorizedException extends AppException {

  public UnauthorizedException(String message) {
    super(401, message);
  }
}
