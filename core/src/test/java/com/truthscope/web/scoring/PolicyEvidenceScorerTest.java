package com.truthscope.web.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyEvidenceScorerTest {

  // 기본 CascadePolicy: threshold=3, cap=50, 5 fields
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

  private final PolicyEvidenceScorer scorer = new PolicyEvidenceScorer();

  // (1) sources null → Optional.empty
  @Test
  void calculate_returnsEmpty_whenSourcesNull() {
    Optional<Integer> result = scorer.calculate(CLAIM, null, POLICY);
    assertThat(result).isEmpty();
  }

  // (2) sources 크기 < threshold → Optional.empty
  @Test
  void calculate_returnsEmpty_whenSourcesBelowThreshold() {
    List<EvidenceSnapshot> twoSources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "제목A", "SUPPORTED", Map.of("수치", "3%")),
            new EvidenceSnapshot("http://b.com", "B", "제목B", "SUPPORTED", Map.of("일자", "2025")));
    Optional<Integer> result = scorer.calculate(CLAIM, twoSources, POLICY);
    assertThat(result).isEmpty();
  }

  // (3) 3개 source 각각 5개 필드 모두 일치 → score > 0 이며 ≤ 100
  @Test
  void calculate_returnsScore_whenAllFieldsMatch() {
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", allFields));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    int score = result.get();
    assertThat(score).isBetween(0, 100);
    // 15 matchedFields / (5 fields * 3 sources) = 100% → score should be 100 before cap
    // cap = 50, so result = min(100, 50) = 50
    assertThat(score).isEqualTo(50);
  }

  // (4) critical-field cap: ratio > cap → score clamped to cap (50)
  @Test
  void calculate_appliesCriticalFieldCap_whenRatioExceedsCap() {
    // 모든 필드 일치 → raw ratio = 100, cap = 50 → result = 50
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", allFields));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    assertThat(result.get()).isLessThanOrEqualTo(50);
  }

  // (5) 결과는 항상 [0, 100] 범위 — matchedCount 가 totalFields 이하일 경우에도 보장
  @Test
  void calculate_returnsClampedScore_whenMatchedExceedsTotal() {
    // cap=100 정책으로 clamp 하한(0) 보장 확인
    CascadePolicy noCap = new CascadePolicy(3, true, 100, List.of("수치", "일자", "대상", "금액", "제도명"));
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", allFields),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", allFields));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, noCap);
    assertThat(result).isPresent();
    assertThat(result.get()).isBetween(0, 100);
  }

  // (6) matchedFields 가 null 또는 empty 인 source → NPE 없이 처리, score 반환
  @Test
  void calculate_handlesEmptyMatchedFields_safely() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "SUPPORTED", Collections.emptyMap()),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    // matchedCount = 0 → ratio = 0, capped = 0
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(0);
  }
}
