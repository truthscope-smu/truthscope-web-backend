package com.truthscope.web.scoring;

import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** claim score를 진실성 5종 라벨로 도출하는 순수 함수(DISCUSS D12). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TruthLabelDeriver {

  /**
   * claim score 0..100을 진실성 5종 TruthLabel로 밴딩 도출한다.
   *
   * @param claimScore 0..100. SCORABLE claim의 score.
   * @param policy 밴드 임계값
   * @throws IllegalArgumentException claimScore가 0..100 밖
   */
  public static TruthLabel deriveTruthLabel(int claimScore, ScoreBandPolicy policy) {
    Objects.requireNonNull(policy, "policy는 null일 수 없다");
    if (claimScore < 0 || claimScore > 100) {
      throw new IllegalArgumentException("claimScore는 0..100이어야 한다: " + claimScore);
    }
    if (claimScore >= policy.factMin()) {
      return TruthLabel.FACT;
    }
    if (claimScore >= policy.mostlyFactMin()) {
      return TruthLabel.MOSTLY_FACT;
    }
    if (claimScore >= policy.partlyFactMin()) {
      return TruthLabel.PARTLY_FACT;
    }
    if (claimScore >= policy.mostlyNotFactMin()) {
      return TruthLabel.MOSTLY_NOT_FACT;
    }
    return TruthLabel.NOT_FACT;
  }
}
