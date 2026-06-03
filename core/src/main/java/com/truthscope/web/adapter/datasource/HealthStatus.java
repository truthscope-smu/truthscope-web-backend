package com.truthscope.web.adapter.datasource;

import java.time.Instant;

public record HealthStatus(String status, long latencyMs, Instant checkedAt) {
  public HealthStatus {
    if (!"UP".equals(status) && !"DOWN".equals(status))
      throw new IllegalArgumentException("status는 UP/DOWN 둘 중 하나");
    if (checkedAt == null) throw new IllegalArgumentException("checkedAt 필수");
  }

  public static HealthStatus up(long latencyMs) {
    return new HealthStatus("UP", latencyMs, Instant.now());
  }

  public static HealthStatus down() {
    return new HealthStatus("DOWN", -1, Instant.now());
  }
}
