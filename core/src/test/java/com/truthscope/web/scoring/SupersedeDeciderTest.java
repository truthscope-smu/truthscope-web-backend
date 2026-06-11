package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.entity.enums.SupersedeReason;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SupersedeDecider 단위 테스트 (ADR-009 4조건 OR, 우선순위 순).
 *
 * <p>fixture ScoreBandPolicy(80,60,40,20): FACT=80이상, MOSTLY_FACT=60이상, PARTLY_FACT=40이상,
 * MOSTLY_NOT_FACT=20이상, NOT_FACT=20미만.
 */
class SupersedeDeciderTest {

  private static final ScoreBandPolicy BAND = new ScoreBandPolicy(80, 60, 40, 20);
  private static final ReVerifyPolicy POLICY = new ReVerifyPolicy(Duration.ofHours(24), 15, 0.30);

  /** 테스트용 라벨 도출 도우미 — TruthLabelDeriver 직접 사용(프로덕션 코드와 동일 경로). */
  private TruthLabel label(int score) {
    return TruthLabelDeriver.deriveTruthLabel(score, BAND);
  }

  // U1 LABEL_CHANGED -----------------------------------------------------------------

  // (U1a) oldLabel=FACT(score 90), newLabel=PARTLY_FACT(score 50), tier 동일, score 차=40(>15),
  // URL 동일 → LABEL_CHANGED 반환 (우선순위 1위)
  @Test
  @DisplayName("U1a LABEL_CHANGED: 라벨 변경 시 LABEL_CHANGED 반환")
  void decide_returnsLABEL_CHANGED_whenLabelDiffers() {
    int oldScore = 90;
    int newScore = 50;
    Set<String> sameUrls = Set.of("http://a.com", "http://b.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.LABEL_CHANGED);
  }

  // (U1b) oldLabel==newLabel(둘 다 FACT) → LABEL_CHANGED 미발동 (동일 라벨 조건 미충족)
  @Test
  @DisplayName("U1b LABEL_CHANGED: 동일 라벨이면 미발동")
  void decide_doesNotReturnLABEL_CHANGED_whenSameLabel() {
    int oldScore = 90;
    int newScore = 85; // 둘 다 FACT (80 이상), 차 = 5(<= 15) → SCORE_DRIFT도 미발동
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isEmpty();
  }

  // U2 SCORE_DRIFT 경계 --------------------------------------------------------------

  // (U2a) old 95, new 80 → 둘 다 FACT, tier 동일(2), 차 = 15 (임계값 15 이하, 정확히 15 = 미발동)
  // → empty (SCORE_DRIFT 미발동)
  @Test
  @DisplayName("U2a SCORE_DRIFT: 차이가 임계값(15)과 정확히 같으면 미발동(초과 조건)")
  void decide_doesNotFireSCORE_DRIFT_whenDiffEqualsThreshold() {
    int oldScore = 95;
    int newScore = 80; // 차 = 15, 둘 다 FACT(라벨 동일), tier 동일
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isEmpty();
  }

  // (U2b) old 96, new 80 → 둘 다 FACT, tier 동일(2), 차 = 16 (임계값 15 초과 → SCORE_DRIFT 발동)
  @Test
  @DisplayName("U2b SCORE_DRIFT: 차이가 임계값(15)을 초과(16)하면 발동")
  void decide_returnsSCORE_DRIFT_whenDiffExceedsThreshold() {
    int oldScore = 96;
    int newScore = 80; // 차 = 16 > 15, 둘 다 FACT(라벨 동일), tier 동일
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.SCORE_DRIFT);
  }

  // U3 URL_REPLACEMENT 경계 ----------------------------------------------------------

  // (U3a) oldUrls 10개, newUrls에 없는 것 3개(30%) → 30% 이상이므로 URL_REPLACEMENT 발동
  @Test
  @DisplayName("U3a URL_REPLACEMENT: 교체 비율이 임계값(30%)과 정확히 같으면 발동(이상 조건)")
  void decide_returnsURL_REPLACEMENT_whenRatioAtThreshold() {
    // 라벨 동일(FACT), tier 동일, 차 <= 15 이므로 LABEL_CHANGED/TIER_CHANGED/SCORE_DRIFT 미발동
    int oldScore = 90;
    int newScore = 88; // 차=2, 둘 다 FACT
    Set<String> oldUrls =
        Set.of(
            "http://1.com",
            "http://2.com",
            "http://3.com",
            "http://4.com",
            "http://5.com",
            "http://6.com",
            "http://7.com",
            "http://8.com",
            "http://9.com",
            "http://10.com");
    // new에는 1~7만 있고 8,9,10은 없음 → 없는 것 3개, 비율 3/10 = 30% (이상이므로 발동)
    Set<String> newUrls =
        Set.of(
            "http://1.com",
            "http://2.com",
            "http://3.com",
            "http://4.com",
            "http://5.com",
            "http://6.com",
            "http://7.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            oldUrls,
            newUrls,
            POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.URL_REPLACEMENT);
  }

  // (U3b) oldUrls 10개, newUrls에 없는 것 2개(20%) → 20% < 30%, URL_REPLACEMENT 미발동
  // 주의: PLAN U3는 "29% 미발동"을 명시했으나 10개 URL 기준 정수 표현 불가(2.9개 = 불가).
  // 정수로 표현 가능한 경계 바로 아래 값인 2개(20%)로 대체. 임계값(30%) 미만임은 동일하게 검증됨.
  @Test
  @DisplayName("U3b URL_REPLACEMENT: 교체 비율이 임계값(30%) 미만(20%)이면 미발동")
  void decide_doesNotFireURL_REPLACEMENT_whenRatioBelowThreshold() {
    int oldScore = 90;
    int newScore = 88;
    Set<String> oldUrls =
        Set.of(
            "http://1.com",
            "http://2.com",
            "http://3.com",
            "http://4.com",
            "http://5.com",
            "http://6.com",
            "http://7.com",
            "http://8.com",
            "http://9.com",
            "http://10.com");
    // new에는 1~8만 있고 9,10은 없음 → 없는 것 2개, 비율 2/10 = 20% (30% 미만이므로 미발동)
    Set<String> newUrls =
        Set.of(
            "http://1.com",
            "http://2.com",
            "http://3.com",
            "http://4.com",
            "http://5.com",
            "http://6.com",
            "http://7.com",
            "http://8.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            oldUrls,
            newUrls,
            POLICY);

    assertThat(result).isEmpty();
  }

  // (U3c) oldUrls 빈 집합 → URL_REPLACEMENT 미발동
  @Test
  @DisplayName("U3c URL_REPLACEMENT: oldUrls가 빈 집합이면 미발동")
  void decide_doesNotFireURL_REPLACEMENT_whenOldUrlsEmpty() {
    int oldScore = 90;
    int newScore = 88;

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            Set.of(),
            Set.of("http://a.com"),
            POLICY);

    assertThat(result).isEmpty();
  }

  // U4 TIER_CHANGED ------------------------------------------------------------------

  // (U4a) tier 2 → 1 변경 → TIER_CHANGED 발동
  @Test
  @DisplayName("U4a TIER_CHANGED: tier가 변경되면 발동")
  void decide_returnsTIER_CHANGED_whenTierDecreases() {
    int oldScore = 70;
    int newScore = 72; // 둘 다 MOSTLY_FACT(라벨 동일), 차=2(<= 15)
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 1, // tier 2 → 1
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.TIER_CHANGED);
  }

  // (U4b) oldScore null(비판정 — tier 3, oldLabel null), newLabel=MOSTLY_FACT(tier 3 → 2) →
  // TIER_CHANGED
  @Test
  @DisplayName("U4b TIER_CHANGED: 비판정(null)에서 SCORABLE로 승격 시 발동")
  void decide_returnsTIER_CHANGED_whenNewlyScorable() {
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            null, 70, // oldScore null = 비판정
            (short) 3, (short) 2, // tier 3 → 2
            null, label(70), // oldLabel null = 비판정
            sameUrls, sameUrls, POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.TIER_CHANGED);
  }

  // U5 전부 미충족 / 우선순위 -----------------------------------------------------------

  // (U5a) 4조건 전부 미충족 → empty
  @Test
  @DisplayName("U5a: 4조건 전부 미충족이면 Optional.empty() 반환")
  void decide_returnsEmpty_whenNoConditionMet() {
    int oldScore = 90;
    int newScore = 88; // 둘 다 FACT, tier 동일, 차=2(<= 15), URL 동일
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isEmpty();
  }

  // (U5b) LABEL_CHANGED + SCORE_DRIFT 동시 충족 → LABEL_CHANGED 반환 (우선순위 1위)
  @Test
  @DisplayName("U5b 우선순위: LABEL_CHANGED와 SCORE_DRIFT 동시 충족 시 LABEL_CHANGED 반환")
  void decide_returnsLABEL_CHANGED_overSCORE_DRIFT_whenBothMet() {
    // old 90(FACT) → new 50(PARTLY_FACT): 라벨 변경 + 차=40(>15)
    int oldScore = 90;
    int newScore = 50;
    Set<String> sameUrls = Set.of("http://a.com");

    Optional<SupersedeReason> result =
        SupersedeDecider.decide(
            oldScore,
            newScore,
            (short) 2,
            (short) 2,
            label(oldScore),
            label(newScore),
            sameUrls,
            sameUrls,
            POLICY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(SupersedeReason.LABEL_CHANGED);
  }
}
