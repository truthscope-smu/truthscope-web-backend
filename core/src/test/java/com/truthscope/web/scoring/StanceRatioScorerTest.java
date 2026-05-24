package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StanceRatioScorerTest {

  private static final CascadePolicy POLICY =
      new CascadePolicy(3, true, 50, List.of("수치", "일자", "대상", "금액", "제도명"));

  private static final ClaimDraft CLAIM =
      new ClaimDraft(
          UUID.randomUUID(),
          "정부는 2025년 GDP 성장률이 3% 라고 발표했다.",
          "기획재정부",
          false,
          null,
          ClaimStatusCandidate.SCORABLE,
          null);

  private final StanceRatioScorer scorer = new StanceRatioScorer();

  // (1) sources 크기 < threshold → Optional.empty
  @Test
  void calculate_returnsEmpty_whenSourcesBelowThreshold() {
    List<EvidenceSnapshot> twoSources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, twoSources, POLICY);
    assertThat(result).isEmpty();
  }

  // (2) 모든 source 가 UNRELATED → denominator = 0 → Optional.empty
  @Test
  void calculate_returnsEmpty_whenAllUnrelated() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "UNRELATED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "UNRELATED", null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "UNRELATED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isEmpty();
  }

  // (3) 3개 모두 SUPPORTED → score 100
  @Test
  void calculate_returns100_whenAllSupported() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(100);
  }

  // (4) 3개 모두 CONTRADICTED → score 0 (cap 30 적용, ratio = 0)
  @Test
  void calculate_returns0_whenAllContradicted() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "CONTRADICTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "CONTRADICTED", null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "CONTRADICTED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // ratio = 0/3 = 0, contradicted > supported → min(30, 0) = 0
    assertThat(result.get()).isEqualTo(0);
  }

  // (5) 충돌 시 cap 30: SUPPORTED 1 + CONTRADICTED 2 → score ≤ 30
  @Test
  void calculate_appliesConflictCap_whenContradictedExceedsSupported() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "CONTRADICTED", null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "CONTRADICTED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // ratio = 1/3 = 33, contradicted(2) > supported(1) → min(30, 33) = 30
    assertThat(result.get()).isLessThanOrEqualTo(30);
    assertThat(result.get()).isEqualTo(30);
  }

  // (6) UNRELATED 는 denominator 에서 제외: SUPPORTED 2 + UNRELATED 1 → ratio = 2/2 = 100
  @Test
  void calculate_excludesUnrelatedFromDenominator() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "UNRELATED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // denominator = 2 (SUPPORTED 2 + CONTRADICTED 0), ratio = 2/2 = 100
    assertThat(result.get()).isEqualTo(100);
  }
}
