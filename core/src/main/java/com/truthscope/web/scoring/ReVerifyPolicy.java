package com.truthscope.web.scoring;

import java.time.Duration;
import java.util.Objects;

/** 재검증 정책 (UC-145). 쿨다운과 supersede 4조건 임계값 — production 값은 yml 주입(ADR-009 4조건). */
public record ReVerifyPolicy(
    Duration cooldown, int scoreDriftThreshold, double urlReplacementRatio) {
  public ReVerifyPolicy {
    Objects.requireNonNull(cooldown, "cooldown은 null일 수 없다");
    if (cooldown.isNegative()) {
      throw new IllegalArgumentException("cooldown은 음수일 수 없다: " + cooldown);
    }
    if (scoreDriftThreshold < 0 || scoreDriftThreshold > 100) {
      throw new IllegalArgumentException(
          "scoreDriftThreshold는 0..100이어야 한다: " + scoreDriftThreshold);
    }
    if (urlReplacementRatio < 0.0 || urlReplacementRatio > 1.0) {
      throw new IllegalArgumentException("urlReplacementRatio는 0..1이어야 한다: " + urlReplacementRatio);
    }
  }
}
