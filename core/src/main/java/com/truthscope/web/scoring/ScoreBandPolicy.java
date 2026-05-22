package com.truthscope.web.scoring;

/**
 * 진실성 5종 밴드의 하한 임계값(포함). claimScore >= factMin이면 FACT, mostlyFactMin 이상이면 MOSTLY_FACT,
 * partlyFactMin 이상이면 PARTLY_FACT, mostlyNotFactMin 이상이면 MOSTLY_NOT_FACT, 그 미만이면 NOT_FACT.
 *
 * <p>임계값 확정은 Phase 55 범위 밖 후속 이연 항목이다(DISCUSS 5장 4절). 함수 시그니처만 고정하고 production 값은 호출자가 주입한다.
 */
public record ScoreBandPolicy(
    int factMin, int mostlyFactMin, int partlyFactMin, int mostlyNotFactMin) {

  public ScoreBandPolicy {
    if (!(factMin > mostlyFactMin
        && mostlyFactMin > partlyFactMin
        && partlyFactMin > mostlyNotFactMin
        && mostlyNotFactMin > 0
        && factMin <= 100)) {
      throw new IllegalArgumentException(
          "밴드 임계값은 0 초과 100 이하 내림차순이어야 한다: "
              + factMin
              + "/"
              + mostlyFactMin
              + "/"
              + partlyFactMin
              + "/"
              + mostlyNotFactMin);
    }
  }
}
