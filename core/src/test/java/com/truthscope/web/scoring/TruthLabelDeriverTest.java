package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class TruthLabelDeriverTest {

  // fixture: ScoreBandPolicy(85, 70, 45, 20)
  private static final ScoreBandPolicy POLICY = new ScoreBandPolicy(85, 70, 45, 20);

  // 경계값 10건 — fixture ScoreBandPolicy(85, 70, 45, 20)
  @Test
  void score_100이면_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(100, POLICY)).isEqualTo(TruthLabel.FACT);
  }

  @Test
  void score_85이면_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(85, POLICY)).isEqualTo(TruthLabel.FACT);
  }

  @Test
  void score_84이면_MOSTLY_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(84, POLICY)).isEqualTo(TruthLabel.MOSTLY_FACT);
  }

  @Test
  void score_70이면_MOSTLY_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(70, POLICY)).isEqualTo(TruthLabel.MOSTLY_FACT);
  }

  @Test
  void score_69이면_PARTLY_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(69, POLICY)).isEqualTo(TruthLabel.PARTLY_FACT);
  }

  @Test
  void score_45이면_PARTLY_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(45, POLICY)).isEqualTo(TruthLabel.PARTLY_FACT);
  }

  @Test
  void score_44이면_MOSTLY_NOT_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(44, POLICY))
        .isEqualTo(TruthLabel.MOSTLY_NOT_FACT);
  }

  @Test
  void score_20이면_MOSTLY_NOT_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(20, POLICY))
        .isEqualTo(TruthLabel.MOSTLY_NOT_FACT);
  }

  @Test
  void score_19이면_NOT_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(19, POLICY)).isEqualTo(TruthLabel.NOT_FACT);
  }

  @Test
  void score_0이면_NOT_FACT() {
    assertThat(TruthLabelDeriver.deriveTruthLabel(0, POLICY)).isEqualTo(TruthLabel.NOT_FACT);
  }

  // 상한 경계 2건 — ScoreBandPolicy(100, 70, 45, 20)
  @Test
  void factMin_100일때_score_100이면_FACT() {
    ScoreBandPolicy policy100 = new ScoreBandPolicy(100, 70, 45, 20);
    assertThat(TruthLabelDeriver.deriveTruthLabel(100, policy100)).isEqualTo(TruthLabel.FACT);
  }

  @Test
  void factMin_100일때_score_99이면_MOSTLY_FACT() {
    ScoreBandPolicy policy100 = new ScoreBandPolicy(100, 70, 45, 20);
    assertThat(TruthLabelDeriver.deriveTruthLabel(99, policy100)).isEqualTo(TruthLabel.MOSTLY_FACT);
  }

  // 예외 — claimScore 범위 밖
  @Test
  void score_마이너스1이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TruthLabelDeriver.deriveTruthLabel(-1, POLICY))
        .withMessageContaining("claimScore는 0..100이어야 한다");
  }

  @Test
  void score_101이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TruthLabelDeriver.deriveTruthLabel(101, POLICY))
        .withMessageContaining("claimScore는 0..100이어야 한다");
  }

  // 예외 — policy null
  @Test
  void policy_null이면_NullPointerException() {
    assertThatNullPointerException().isThrownBy(() -> TruthLabelDeriver.deriveTruthLabel(50, null));
  }

  // ScoreBandPolicy 불변식 — 비내림차순 인자 (mostlyFactMin > factMin)
  @Test
  void ScoreBandPolicy_비내림차순이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ScoreBandPolicy(70, 85, 45, 20))
        .withMessageContaining("밴드 임계값은 0 초과 100 이하 내림차순이어야 한다");
  }

  // ScoreBandPolicy 불변식 — mostlyNotFactMin = 0 (0 초과 조건 위반)
  @Test
  void ScoreBandPolicy_mostlyNotFactMin_0이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ScoreBandPolicy(85, 70, 45, 0))
        .withMessageContaining("밴드 임계값은 0 초과 100 이하 내림차순이어야 한다");
  }
}
