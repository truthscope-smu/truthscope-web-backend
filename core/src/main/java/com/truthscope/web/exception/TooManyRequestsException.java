package com.truthscope.web.exception;

/** 재검증 쿨다운 미충족 예외 — 429 상태 코드 */
public class TooManyRequestsException extends AppException {

  public TooManyRequestsException(String message) {
    super(429, message);
  }
}
