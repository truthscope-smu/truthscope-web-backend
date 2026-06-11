package com.truthscope.web.scoring;

import com.truthscope.web.entity.enums.SupersedeReason;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 재검증 결과가 기존 판정을 supersede해야 하는지 여부를 판별하는 순수 함수(ADR-009 4조건 OR).
 *
 * <p>조건 우선순위 (첫 번째 충족 사유를 반환):
 *
 * <ol>
 *   <li>LABEL_CHANGED: oldLabel != null &amp;&amp; newLabel != null &amp;&amp; oldLabel != newLabel
 *   <li>TIER_CHANGED: oldTier != newTier
 *   <li>SCORE_DRIFT: |newScore - oldScore| > scoreDriftThreshold (정확히 임계값이면 미발동 — "초과")
 *   <li>URL_REPLACEMENT: !oldUrls.isEmpty() &amp;&amp; 교체 비율 &gt;= urlReplacementRatio ("이상" — 정확히
 *       30%이면 발동)
 * </ol>
 *
 * <p>경계 의미 비대칭: SCORE_DRIFT는 "초과"(임계값 미포함), URL_REPLACEMENT는 "이상"(임계값 포함). ADR-009 원문("점수 차 15 초과"
 * vs "30% 이상") 그대로 구현한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SupersedeDecider {

  /**
   * supersede 사유를 판별한다.
   *
   * @param oldScore 기존 판정 점수. 비판정(Tier 3)이면 null.
   * @param newScore 재검증 점수. 비판정이면 null.
   * @param oldTier 기존 tier (1, 2, 3).
   * @param newTier 재검증 tier (1, 2, 3).
   * @param oldLabel 기존 TruthLabel. 비판정이면 null.
   * @param newLabel 재검증 TruthLabel. 비판정이면 null.
   * @param oldUrls 기존 판정에 사용된 출처 URL 집합.
   * @param newUrls 재검증에 사용된 출처 URL 집합.
   * @param policy 재검증 정책 (scoreDriftThreshold, urlReplacementRatio).
   * @return 첫 번째로 충족된 SupersedeReason, 전부 미충족이면 Optional.empty().
   */
  public static Optional<SupersedeReason> decide(
      Integer oldScore,
      Integer newScore,
      short oldTier,
      short newTier,
      TruthLabel oldLabel,
      TruthLabel newLabel,
      Set<String> oldUrls,
      Set<String> newUrls,
      ReVerifyPolicy policy) {

    Objects.requireNonNull(oldUrls, "oldUrls는 null일 수 없다");
    Objects.requireNonNull(newUrls, "newUrls는 null일 수 없다");
    Objects.requireNonNull(policy, "policy는 null일 수 없다");

    // 1. LABEL_CHANGED: 라벨이 모두 존재하며 다를 때
    if (oldLabel != null && newLabel != null && oldLabel != newLabel) {
      return Optional.of(SupersedeReason.LABEL_CHANGED);
    }

    // 2. TIER_CHANGED: tier가 변경됐을 때
    if (oldTier != newTier) {
      return Optional.of(SupersedeReason.TIER_CHANGED);
    }

    // 3. SCORE_DRIFT: 점수 차가 임계값을 "초과"할 때 (정확히 임계값이면 미발동)
    if (oldScore != null && newScore != null) {
      if (Math.abs(oldScore - newScore) > policy.scoreDriftThreshold()) {
        return Optional.of(SupersedeReason.SCORE_DRIFT);
      }
    }

    // 4. URL_REPLACEMENT: 교체 비율이 urlReplacementRatio "이상"일 때 (정확히 임계값이면 발동)
    if (!oldUrls.isEmpty()) {
      long removedCount = oldUrls.stream().filter(url -> !newUrls.contains(url)).count();
      double replacementRatio = (double) removedCount / oldUrls.size();
      if (replacementRatio >= policy.urlReplacementRatio()) {
        return Optional.of(SupersedeReason.URL_REPLACEMENT);
      }
    }

    return Optional.empty();
  }
}
