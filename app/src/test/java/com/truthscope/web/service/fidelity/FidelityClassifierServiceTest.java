package com.truthscope.web.service.fidelity;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.scoring.EvidenceSnapshot;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * applyRelevanceFilter 단위 테스트. refuting evidence 보존(field 단위 대조가 성립하면 matched 0이어도 통과)이
 * FEVER/SciFact/AVeriTeC/FNC-1 설계 원칙과 정합함을 검증한다.
 */
class FidelityClassifierServiceTest {

  private static EvidenceSnapshot snap(
      String stance, Map<String, String> matched, Map<String, String> mismatched) {
    return new EvidenceSnapshot("http://a.example", "출처", "제목", stance, matched, mismatched);
  }

  @Test
  @DisplayName("CONTRADICTED + matched 0 + mismatched 1 → 통과 (순수 반박 evidence 보존)")
  void contradicted_pureMismatch_passes() {
    List<EvidenceSnapshot> in =
        List.of(snap("CONTRADICTED", Collections.emptyMap(), Map.of("수치", "2.8%")));
    assertThat(FidelityClassifierService.applyRelevanceFilter(in)).hasSize(1);
  }

  @Test
  @DisplayName("CONTRADICTED + matched 0 + mismatched 0 → 탈락 (대조 0건)")
  void contradicted_noComparison_rejected() {
    List<EvidenceSnapshot> in =
        List.of(snap("CONTRADICTED", Collections.emptyMap(), Collections.emptyMap()));
    assertThat(FidelityClassifierService.applyRelevanceFilter(in)).isEmpty();
  }

  @Test
  @DisplayName("SUPPORTED + matched 1 → 통과 (기존 동작 유지)")
  void supported_withMatch_passes() {
    List<EvidenceSnapshot> in =
        List.of(snap("SUPPORTED", Map.of("제도명", "성장률 발표"), Collections.emptyMap()));
    assertThat(FidelityClassifierService.applyRelevanceFilter(in)).hasSize(1);
  }

  @Test
  @DisplayName("순수 반박 출처 1건이 필터를 통과해 scoring 까지 도달한다 (Tier3 insufficient 회귀 방지)")
  void pureRefutation_reachesScoring_notDropped() {
    // matched 0 + mismatched>0 인 CONTRADICTED 출처만 있는 경우 과거에는 필터에서 전부 제거되어
    // validSnapshots=0 -> Tier3 INSUFFICIENT 로 빠졌다. 이제 1건 이상 남아 sourceCountThreshold(1)를
    // 충족하므로 tryTier2Score 에서 PolicyEvidenceScorer 까지 도달한다.
    List<EvidenceSnapshot> in =
        List.of(snap("CONTRADICTED", Collections.emptyMap(), Map.of("수치", "다른 값")));
    List<EvidenceSnapshot> survived = FidelityClassifierService.applyRelevanceFilter(in);
    assertThat(survived).isNotEmpty();
    assertThat(survived.get(0).stance()).isEqualTo("CONTRADICTED");
  }

  @Test
  @DisplayName("NEUTRAL stance는 matched가 있어도 탈락")
  void neutral_rejected() {
    List<EvidenceSnapshot> in =
        List.of(snap("NEUTRAL", Map.of("일자", "2025"), Collections.emptyMap()));
    assertThat(FidelityClassifierService.applyRelevanceFilter(in)).isEmpty();
  }
}
