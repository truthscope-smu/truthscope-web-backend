package com.truthscope.web.scoring;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 기사 종합 사실 검증 점수를 검증 가능 claim의 기하평균 계열로 집계하는 순수 함수(DISCUSS D13).
 *
 * <p>SCORABLE claim만 대상으로 한다. 비판정 3종은 score가 null이며 집계에서 제외한다. 검증 가능 claim이 0개면 Optional.empty()를
 * 반환한다(UI는 검증불가 표시).
 *
 * <p>계산은 log 공간에서 한다. 각 score를 scoreFloor로 클램프(max)해 0점 곱셈 붕괴를 막은 뒤 exp(평균(ln(clamped)))를 구하고
 * policy.rounding() 모드로 0..100 정수로 만든다. rounding 모드는 후속 이연 정책값이라 ArticleScorePolicy로 주입받는다. cap이나
 * penalty를 추가 결합하지 않는다(기하평균 자체가 낮은 claim을 끌어내려 이중 처벌 방지).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArticleFactScoreAggregator {

  public static Optional<ArticleFactScore> aggregateArticleFactScore(
      List<ClaimVerificationSignal> claims, ArticleScorePolicy policy) {
    Objects.requireNonNull(claims, "claims는 null일 수 없다");
    Objects.requireNonNull(policy, "policy는 null일 수 없다");

    List<Integer> scores =
        claims.stream()
            .filter(c -> c.status() == ClaimScoreStatus.SCORABLE)
            .map(ClaimVerificationSignal::score)
            .toList();
    if (scores.isEmpty()) {
      return Optional.empty();
    }

    double sumOfLogs = 0.0;
    for (int score : scores) {
      double clamped = Math.max(score, policy.scoreFloor());
      sumOfLogs += Math.log(clamped);
    }
    double geoMean = Math.exp(sumOfLogs / scores.size());
    int rounded = BigDecimal.valueOf(geoMean).setScale(0, policy.rounding()).intValue();
    int bounded = Math.min(100, Math.max(0, rounded));
    return Optional.of(new ArticleFactScore(bounded));
  }
}
