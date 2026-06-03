package com.truthscope.web.adapter.datasource;

import java.time.Instant;

/**
 * 일반 데이터 소스 어댑터의 claim 추출 결과 (Tier 1 cascade 진입 가능).
 *
 * <p>[H1 amend] 구 ExtractedClaim을 DatasourceClaim으로 rename. 기존 dto/response/ExtractedClaim(DTO
 * 계층)과의 이름 충돌 방지. WikipediaAdapter는 이 타입을 직접 반환하지 않는다 — 대신 WikipediaMetaSignal 반환.
 */
public record DatasourceClaim(
    String claimText, String sourceUrl, String lang, Instant extractedAt) {
  public DatasourceClaim {
    if (claimText == null || claimText.isBlank())
      throw new IllegalArgumentException("claimText는 null/blank 금지");
    if (extractedAt == null) throw new IllegalArgumentException("extractedAt 필수");
  }
}
