package com.truthscope.web.adapter.datasource;

public record AdapterMetadata(String name, String version, boolean isPaid, String provider) {
  public AdapterMetadata {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 필수");
    if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+"))
      throw new IllegalArgumentException("version은 시맨틱 버전 (예: 1.0.0)");
  }
}
