package com.truthscope.web.scoring;

import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * claim별 출처 표기 투명성 3단계를 기사 수준 분포와 보수 경고 밴드로 집계하는 순수 함수(DISCUSS D14).
 *
 * <p>분포(명시/모호/없음 개수)가 1차 표시. band 규칙:
 *
 * <ul>
 *   <li>NONE이 1개 이상이면 MISSING_SOURCE(출처 누락 있음).
 *   <li>NONE이 0이고 AMBIGUOUS가 1개 이상이면 SOME_UNCLEAR(일부 불명확).
 *   <li>전부 EXPLICIT이면 ALL_EXPLICIT(모두 명시).
 * </ul>
 *
 * <p>빈 리스트는 분포 (0,0,0) + ALL_EXPLICIT을 반환한다(NONE 0 그리고 AMBIGUOUS 0 규칙의 공허참). 모든 claim(비판정 포함)이
 * sourceTransparency를 가지므로 집계 대상에서 제외하지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SourceTransparencyAggregator {

  public static SourceTransparencySummary aggregateSourceTransparency(
      List<ClaimVerificationSignal> claims) {
    Objects.requireNonNull(claims, "claims는 null일 수 없다");
    int explicit = 0;
    int ambiguous = 0;
    int none = 0;
    for (ClaimVerificationSignal c : claims) {
      switch (c.sourceTransparency()) {
        case EXPLICIT -> explicit++;
        case AMBIGUOUS -> ambiguous++;
        case NONE -> none++;
      }
    }
    SourceTransparencyBand band;
    if (none > 0) {
      band = SourceTransparencyBand.MISSING_SOURCE;
    } else if (ambiguous > 0) {
      band = SourceTransparencyBand.SOME_UNCLEAR;
    } else {
      band = SourceTransparencyBand.ALL_EXPLICIT;
    }
    return new SourceTransparencySummary(explicit, ambiguous, none, band);
  }
}
