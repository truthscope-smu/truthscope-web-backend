package com.truthscope.web.adapter.datasource;

public record RawResponse(String body, int statusCode, String format) {
  public RawResponse {
    if (body == null) throw new IllegalArgumentException("body는 null 금지");
    if (format == null || format.isBlank()) throw new IllegalArgumentException("format 필수");
  }
}
