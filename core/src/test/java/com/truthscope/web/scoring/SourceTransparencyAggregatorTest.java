package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceTransparencyAggregatorTest {

  private static ClaimVerificationSignal signal(
      ClaimScoreStatus status, Integer score, SourceTransparency transparency) {
    return new ClaimVerificationSignal(UUID.randomUUID(), (short) 1, score, status, transparency);
  }

  private static ClaimVerificationSignal explicit() {
    return signal(ClaimScoreStatus.SCORABLE, 80, SourceTransparency.EXPLICIT);
  }

  private static ClaimVerificationSignal ambiguous() {
    return signal(ClaimScoreStatus.SCORABLE, 70, SourceTransparency.AMBIGUOUS);
  }

  private static ClaimVerificationSignal none() {
    return signal(ClaimScoreStatus.INSUFFICIENT, null, SourceTransparency.NONE);
  }

  // 케이스 1: 빈 리스트 → (0,0,0, ALL_EXPLICIT)
  @Test
  void 빈_리스트이면_ALL_EXPLICIT() {
    SourceTransparencySummary result =
        SourceTransparencyAggregator.aggregateSourceTransparency(List.of());
    assertThat(result.explicitCount()).isEqualTo(0);
    assertThat(result.ambiguousCount()).isEqualTo(0);
    assertThat(result.noneCount()).isEqualTo(0);
    assertThat(result.band()).isEqualTo(SourceTransparencyBand.ALL_EXPLICIT);
  }

  // 케이스 2: 전부 EXPLICIT 3개 → (3,0,0, ALL_EXPLICIT)
  @Test
  void 전부_EXPLICIT_3개이면_ALL_EXPLICIT() {
    List<ClaimVerificationSignal> claims = List.of(explicit(), explicit(), explicit());
    SourceTransparencySummary result =
        SourceTransparencyAggregator.aggregateSourceTransparency(claims);
    assertThat(result.explicitCount()).isEqualTo(3);
    assertThat(result.ambiguousCount()).isEqualTo(0);
    assertThat(result.noneCount()).isEqualTo(0);
    assertThat(result.band()).isEqualTo(SourceTransparencyBand.ALL_EXPLICIT);
  }

  // 케이스 3: EXPLICIT 2 + NONE 1 → (2,0,1, MISSING_SOURCE)
  @Test
  void EXPLICIT_2와_NONE_1이면_MISSING_SOURCE() {
    List<ClaimVerificationSignal> claims = List.of(explicit(), explicit(), none());
    SourceTransparencySummary result =
        SourceTransparencyAggregator.aggregateSourceTransparency(claims);
    assertThat(result.explicitCount()).isEqualTo(2);
    assertThat(result.ambiguousCount()).isEqualTo(0);
    assertThat(result.noneCount()).isEqualTo(1);
    assertThat(result.band()).isEqualTo(SourceTransparencyBand.MISSING_SOURCE);
  }

  // 케이스 4: EXPLICIT 1 + AMBIGUOUS 1, NONE 0 → (1,1,0, SOME_UNCLEAR)
  @Test
  void EXPLICIT_1과_AMBIGUOUS_1이면_SOME_UNCLEAR() {
    List<ClaimVerificationSignal> claims = List.of(explicit(), ambiguous());
    SourceTransparencySummary result =
        SourceTransparencyAggregator.aggregateSourceTransparency(claims);
    assertThat(result.explicitCount()).isEqualTo(1);
    assertThat(result.ambiguousCount()).isEqualTo(1);
    assertThat(result.noneCount()).isEqualTo(0);
    assertThat(result.band()).isEqualTo(SourceTransparencyBand.SOME_UNCLEAR);
  }

  // 케이스 5: AMBIGUOUS 1 + NONE 1 → (0,1,1, MISSING_SOURCE) — NONE 우선
  @Test
  void AMBIGUOUS_1과_NONE_1이면_MISSING_SOURCE_NONE_우선() {
    List<ClaimVerificationSignal> claims = List.of(ambiguous(), none());
    SourceTransparencySummary result =
        SourceTransparencyAggregator.aggregateSourceTransparency(claims);
    assertThat(result.explicitCount()).isEqualTo(0);
    assertThat(result.ambiguousCount()).isEqualTo(1);
    assertThat(result.noneCount()).isEqualTo(1);
    assertThat(result.band()).isEqualTo(SourceTransparencyBand.MISSING_SOURCE);
  }

  // 케이스 6: claims null → NPE
  @Test
  void claims_null이면_NullPointerException() {
    assertThatNullPointerException()
        .isThrownBy(() -> SourceTransparencyAggregator.aggregateSourceTransparency(null));
  }
}
