package com.truthscope.web.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * BYOK userApiKey의 SHA-256 hex digest 앞 16 hex 문자(8바이트) 반환.
 *
 * <p>ADR-004 §(f) "key_fingerprint(해시 앞 8자)" 정합 — DISCUSS rev.3 Q6에서 SHA-256 hex 16자(=8바이트)로 명문화.
 * 원본 키는 절대 반환·로그 박제 금지.
 *
 * <p>위치 선택: {@code com.truthscope.web.audit} (non-service 패키지) — ArchitectureTest의 {@code
 * ..service..} Service 접미사 의무 룰을 회피.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KeyFingerprinter {

  private static final int FINGERPRINT_HEX_LENGTH = 16;
  private static final int FINGERPRINT_BYTE_LENGTH = FINGERPRINT_HEX_LENGTH / 2;

  /**
   * userApiKey의 SHA-256 hex digest 앞 16 hex 문자 반환.
   *
   * @param userApiKey 사용자 Gemini API 키 (null/blank 금지)
   * @return 16자 hex 문자열 (소문자)
   * @throws IllegalArgumentException userApiKey가 null/blank인 경우
   */
  public static String fingerprint(String userApiKey) {
    if (userApiKey == null || userApiKey.isBlank()) {
      throw new IllegalArgumentException("userApiKey는 null/blank일 수 없습니다");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(userApiKey.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(FINGERPRINT_HEX_LENGTH);
      for (int i = 0; i < FINGERPRINT_BYTE_LENGTH; i++) {
        hex.append(String.format("%02x", hash[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
