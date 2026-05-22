package com.truthscope.web.scoring;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * 기사 종합 점수 기하평균 계산 정책. scoreFloor는 0점 claim의 곱셈 붕괴를 막는 양수 하한이다 (DISCUSS D13). scoreFloor 값과
 * rounding 모드 둘 다 Phase 55 범위 밖 후속 이연 항목이며 (DISCUSS 5장 3절, FORMULA-DETAIL-ANALYSIS.md 5절), 함수는 이 둘을
 * 정책 파라미터로 받는다. production 값 확정 전까지는 호출자(또는 테스트 fixture)가 주입한다.
 */
public record ArticleScorePolicy(double scoreFloor, RoundingMode rounding) {
  public ArticleScorePolicy {
    Objects.requireNonNull(rounding, "rounding은 null일 수 없다");
    if (rounding == RoundingMode.UNNECESSARY) {
      throw new IllegalArgumentException(
          "rounding은 UNNECESSARY일 수 없다 — 기하평균은 비정수이므로 ArithmeticException 위험");
    }
    if (!(scoreFloor > 0.0 && scoreFloor <= 100.0)) {
      throw new IllegalArgumentException("scoreFloor는 0 초과 100 이하 양수여야 한다: " + scoreFloor);
    }
  }
}
