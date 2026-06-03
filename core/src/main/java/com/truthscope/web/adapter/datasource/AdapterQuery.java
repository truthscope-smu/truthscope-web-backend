package com.truthscope.web.adapter.datasource;

import java.time.LocalDate;

/**
 * 검색 쿼리 값 객체. keyword/limit 필수, lang/fromDate/toDate nullable.
 *
 * @param keyword 검색 키워드 (null/blank 금지)
 * @param lang ISO 639-1 (예: "ko", "en"), nullable
 * @param fromDate 검색 시작일 (inclusive, nullable)
 * @param toDate 검색 종료일 (inclusive, nullable)
 * @param limit 최대 결과 건수 (1-100)
 */
public record AdapterQuery(
    String keyword, String lang, LocalDate fromDate, LocalDate toDate, int limit) {
  public AdapterQuery {
    if (keyword == null || keyword.isBlank()) {
      throw new IllegalArgumentException("keyword는 null/blank 금지");
    }
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit은 1-100 범위");
    }
  }
}
