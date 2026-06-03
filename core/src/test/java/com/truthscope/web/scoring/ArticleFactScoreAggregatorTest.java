package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleFactScoreAggregatorTest {

  // fixture: ArticleScorePolicy(1.0, RoundingMode.HALF_UP)
  private static final ArticleScorePolicy POLICY =
      new ArticleScorePolicy(1.0, RoundingMode.HALF_UP);

  private static ClaimVerificationSignal scorable(int score) {
    return new ClaimVerificationSignal(
        UUID.randomUUID(),
        (short) 1,
        score,
        ClaimScoreStatus.SCORABLE,
        SourceTransparency.EXPLICIT);
  }

  private static ClaimVerificationSignal nonScorable(ClaimScoreStatus status) {
    return new ClaimVerificationSignal(
        UUID.randomUUID(), (short) 2, null, status, SourceTransparency.NONE);
  }

  // 케이스 1: 빈 리스트 → Optional.empty
  @Test
  void 빈_리스트이면_Optional_empty() {
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(List.of(), POLICY);
    assertThat(result).isEmpty();
  }

  // 케이스 2: 전부 비판정(검증 가능 0개) → Optional.empty
  @Test
  void 전부_비판정이면_Optional_empty() {
    List<ClaimVerificationSignal> claims =
        List.of(
            nonScorable(ClaimScoreStatus.INSUFFICIENT),
            nonScorable(ClaimScoreStatus.TIME_SENSITIVE),
            nonScorable(ClaimScoreStatus.OUT_OF_SCOPE));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isEmpty();
  }

  // 케이스 3: 단일 SCORABLE score 80 → value 80
  @Test
  void 단일_SCORABLE_80이면_value_80() {
    List<ClaimVerificationSignal> claims = List.of(scorable(80));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(80);
  }

  // 케이스 4: SCORABLE [90,90,20] → 기하평균 value 55, 산술평균 67보다 낮음 단언
  // 검증: exp((ln90+ln90+ln20)/3) = exp(3.99845) = 54.513 → HALF_UP setScale(0) = 55
  // 산술평균 (90+90+20)/3 = 66.67 → HALF_UP = 67
  @Test
  void SCORABLE_90_90_20이면_기하평균_55_산술평균_67보다_낮음() {
    List<ClaimVerificationSignal> claims = List.of(scorable(90), scorable(90), scorable(20));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isPresent();
    int geoMeanValue = result.get().value();
    assertThat(geoMeanValue).isEqualTo(55);
    // 기하평균(55)이 산술평균(67)보다 낮음을 명시 단언 — 산식이 실제로 기하평균임을 검증
    int arithmeticMean = (90 + 90 + 20) / 3; // 66 (정수 나눗셈), HALF_UP → 67
    assertThat(geoMeanValue).isLessThan(67);
    assertThat(arithmeticMean).isEqualTo(66); // 정수 나눗셈 66, 실수 66.67 → 67
  }

  // 케이스 5: SCORABLE [0,100] → floor 1.0 클램프로 value 10, 0 붕괴 아님
  // 검증: exp((ln1+ln100)/2) = exp((0+4.60517)/2) = exp(2.30259) = 10.0 → HALF_UP = 10
  @Test
  void SCORABLE_0과_100이면_floor_클램프로_value_10() {
    List<ClaimVerificationSignal> claims = List.of(scorable(0), scorable(100));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(10);
  }

  // 케이스 6: SCORABLE 단일 [0] → floor 1.0 단독 클램프로 value 1
  // 검증: exp(ln1) = exp(0) = 1.0 → HALF_UP setScale(0) = 1
  @Test
  void SCORABLE_단일_0이면_floor_1_클램프로_value_1() {
    List<ClaimVerificationSignal> claims = List.of(scorable(0));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isPresent();
    assertThat(result.get().value()).isEqualTo(1);
  }

  // 케이스 7: SCORABLE 2개 + INSUFFICIENT 1개 + OUT_OF_SCOPE 1개 혼합 → SCORABLE 2개만 집계
  @Test
  void SCORABLE_2개와_비판정_혼합이면_SCORABLE만_집계() {
    List<ClaimVerificationSignal> claims =
        List.of(
            scorable(80),
            scorable(80),
            nonScorable(ClaimScoreStatus.INSUFFICIENT),
            nonScorable(ClaimScoreStatus.OUT_OF_SCOPE));
    Optional<ArticleFactScore> result =
        ArticleFactScoreAggregator.aggregateArticleFactScore(claims, POLICY);
    assertThat(result).isPresent();
    // exp((ln80+ln80)/2) = 80 → value 80
    assertThat(result.get().value()).isEqualTo(80);
  }

  // 케이스 8: claims null → NPE
  @Test
  void claims_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(() -> ArticleFactScoreAggregator.aggregateArticleFactScore(null, POLICY));
  }

  // 케이스 9: ArticleScorePolicy 불변식
  @Test
  void ArticleScorePolicy_scoreFloor_0이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ArticleScorePolicy(0.0, RoundingMode.HALF_UP))
        .withMessageContaining("scoreFloor는 0 초과 100 이하 양수여야 한다");
  }

  @Test
  void ArticleScorePolicy_scoreFloor_101이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ArticleScorePolicy(101.0, RoundingMode.HALF_UP))
        .withMessageContaining("scoreFloor는 0 초과 100 이하 양수여야 한다");
  }

  @Test
  void ArticleScorePolicy_rounding_null이면_NullPointerException() {
    assertThatNullPointerException().isThrownBy(() -> new ArticleScorePolicy(1.0, null));
  }

  @Test
  void ArticleScorePolicy_rounding_UNNECESSARY이면_IllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ArticleScorePolicy(1.0, RoundingMode.UNNECESSARY))
        .withMessageContaining("rounding은 UNNECESSARY일 수 없다");
  }
}
