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
  // NOTE: threshold=3 은 test-local 픽스처 의도이며 application.yml source-count-threshold=1 과 다름.
  // cap 활성화 테스트(test 7, 8)는 source 3건을 사용해 scorer 가 실제 발동하도록 한다.
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
            new EvidenceSnapshot(
                "http://a.com",
                "A",
                "제목A",
                "SUPPORTED",
                Map.of("수치", "3%"),
                Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://b.com",
                "B",
                "제목B",
                "SUPPORTED",
                Map.of("일자", "2025"),
                Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, twoSources, POLICY);
    assertThat(result).isEmpty();
  }

  // (3) 3개 source 각각 5개 필드 모두 일치, mismatch 없음 → cap 해제, score = 100
  @Test
  void calculate_returnsScore_whenAllFieldsMatch() {
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot(
                "http://a.com", "A", "A제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com", "C", "C제목", "SUPPORTED", allFields, Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    int score = result.get();
    assertThat(score).isBetween(0, 100);
    // 15 matchedFields / (5 fields * 3 sources) = 100% ratio, mismatch 없음 -> cap 해제 -> score = 100
    assertThat(score).isEqualTo(100);
  }

  // (4) claimSplitFields 에 없는 키만 mismatched 에 넣어 cap 미발동(score 100) 검증
  @Test
  void calculate_doesNotApplyCap_whenMismatchOnNonCriticalKey() {
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    // "기타" 는 claimSplitFields 에 없는 비-critical 키이므로 cap 미발동
    Map<String, String> nonCriticalMismatch = Map.of("기타", "무관한 값");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot(
                "http://a.com", "A", "A제목", "SUPPORTED", allFields, nonCriticalMismatch),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com", "C", "C제목", "SUPPORTED", allFields, Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // hasCriticalMismatch = false ("기타" not in claimSplitFields) -> cap 미발동 -> score = 100
    assertThat(result.get()).isEqualTo(100);
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
            new EvidenceSnapshot(
                "http://a.com",
                "A",
                "A제목",
                "SUPPORTED",
                allFields,
                java.util.Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://b.com",
                "B",
                "B제목",
                "SUPPORTED",
                allFields,
                java.util.Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com",
                "C",
                "C제목",
                "SUPPORTED",
                allFields,
                java.util.Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, noCap);
    assertThat(result).isPresent();
    assertThat(result.get()).isBetween(0, 100);
  }

  // (6) matchedFields 가 null 또는 empty 인 source → NPE 없이 처리, score 반환
  @Test
  void calculate_handlesEmptyMatchedFields_safely() {
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", null, null),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", Collections.emptyMap(), null),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "SUPPORTED", null, null));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    // matchedCount = 0 -> ratio = 0, mismatch null -> hasCriticalMismatch = false -> capped = 0
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(0);
  }

  // (7) critical field 불일치 감지: 수치 필드 mismatch -> cap 활성화 -> score == 50
  @Test
  void calculate_appliesCap_whenCriticalFieldMismatched() {
    // 수치 필드 불일치: claim 은 3%, 원문은 2.8%
    Map<String, String> matched = Map.of("일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    Map<String, String> mismatched = Map.of("수치", "2.8%"); // critical field mismatch
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "SUPPORTED", matched, mismatched),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", matched, java.util.Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com",
                "C",
                "C제목",
                "SUPPORTED",
                matched,
                java.util.Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // hasCriticalMismatch=true (수치 in claimSplitFields), ratio=(4*3)/(5*3)*100=80, cap=50 ->
    // score=50
    assertThat(result.get()).isEqualTo(50);
  }

  // (8) mismatch 없는 시나리오: cap 해제 -> score = 100
  @Test
  void calculate_liftsCap_whenNoCriticalMismatch() {
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot(
                "http://a.com", "A", "A제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com", "C", "C제목", "SUPPORTED", allFields, Collections.emptyMap()));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // hasCriticalMismatch=false -> cap 해제 -> ratio=100 -> score=100
    assertThat(result.get()).isEqualTo(100);
  }

  // (9) stance 정합: CONTRADICTED 출처의 matchedFields 는 양성 점수에 합산되지 않는다 -> 순수 반박 score = 0
  @Test
  void calculate_excludesContradictedMatchedFields_scoresZero() {
    Map<String, String> matched = Map.of("일자", "2025", "대상", "GDP");
    Map<String, String> mismatched = Map.of("수치", "2.8%");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot("http://a.com", "A", "A제목", "CONTRADICTED", matched, mismatched),
            new EvidenceSnapshot("http://b.com", "B", "B제목", "CONTRADICTED", matched, mismatched),
            new EvidenceSnapshot("http://c.com", "C", "C제목", "CONTRADICTED", matched, mismatched));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // 모든 출처가 CONTRADICTED -> matchedCount 0 -> ratio 0 -> 수치 mismatch cap min(0,50)=0
    assertThat(result.get()).isEqualTo(0);
  }

  // (10) 혼합 stance: SUPPORTED matched 만 합산, CONTRADICTED 는 분모만 차지해 점수 희석
  @Test
  void calculate_mixedStance_countsOnlySupportedMatches() {
    Map<String, String> allFields =
        Map.of("수치", "3%", "일자", "2025", "대상", "GDP", "금액", "N/A", "제도명", "성장률 발표");
    Map<String, String> mismatched = Map.of("수치", "2.8%");
    List<EvidenceSnapshot> sources =
        List.of(
            new EvidenceSnapshot(
                "http://a.com", "A", "A제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://b.com", "B", "B제목", "SUPPORTED", allFields, Collections.emptyMap()),
            new EvidenceSnapshot(
                "http://c.com", "C", "C제목", "CONTRADICTED", allFields, mismatched));
    Optional<Integer> result = scorer.calculate(CLAIM, sources, POLICY);
    assertThat(result).isPresent();
    // numerator = 5+5 (SUPPORTED 2건만) = 10, denom = 5*3 = 15 -> ratio 66, 수치(critical) mismatch ->
    // cap 50
    assertThat(result.get()).isEqualTo(50);
  }
}
