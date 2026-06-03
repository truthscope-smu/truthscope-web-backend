package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.scoring.EvidenceSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AnalysisTransactionService 단위 테스트.
 *
 * <p>verdict 매핑의 stance 다수결 로직(isMajorityContradicted)을 직접 검증한다. 영속화 경로 전체는 Repository 다수 의존으로 통합
 * 테스트(VerificationPipelineIntegrationTest) 소관이며, 여기서는 순수 판정 헬퍼만 검증한다.
 */
@DisplayName("AnalysisTransactionService.isMajorityContradicted")
class AnalysisTransactionServiceTest {

  private EvidenceSnapshot ev(String stance) {
    return new EvidenceSnapshot("http://x", "p", "t", stance, Map.of(), Map.of());
  }

  @Test
  @DisplayName("반박(CONTRADICTED) 출처가 더 많으면 true")
  void 반박_우세면_true() {
    assertThat(
            AnalysisTransactionService.isMajorityContradicted(
                List.of(ev("CONTRADICTED"), ev("CONTRADICTED"), ev("SUPPORTED"))))
        .isTrue();
  }

  @Test
  @DisplayName("순수 반박 1건이면 true")
  void 순수_반박_1건이면_true() {
    assertThat(AnalysisTransactionService.isMajorityContradicted(List.of(ev("CONTRADICTED"))))
        .isTrue();
  }

  @Test
  @DisplayName("뒷받침 우세 또는 동률이면 false (뒷받침으로 간주)")
  void 뒷받침_우세_또는_동률이면_false() {
    assertThat(
            AnalysisTransactionService.isMajorityContradicted(
                List.of(ev("SUPPORTED"), ev("SUPPORTED"), ev("CONTRADICTED"))))
        .isFalse();
    // 동률 -> 뒷받침으로 간주
    assertThat(
            AnalysisTransactionService.isMajorityContradicted(
                List.of(ev("SUPPORTED"), ev("CONTRADICTED"))))
        .isFalse();
  }

  @Test
  @DisplayName("evidence 없음(Tier 1 캐시 히트 등)이면 false")
  void evidence_없으면_false() {
    assertThat(AnalysisTransactionService.isMajorityContradicted(List.of())).isFalse();
    assertThat(AnalysisTransactionService.isMajorityContradicted(null)).isFalse();
  }

  @Test
  @DisplayName("NEUTRAL 은 양쪽 모두 아니므로 카운트 제외 — 뒷받침/반박 동수면 false")
  void neutral_제외() {
    // CONTRADICTED 1 vs SUPPORTED 1 (+ NEUTRAL 1) -> 동률 -> false
    assertThat(
            AnalysisTransactionService.isMajorityContradicted(
                List.of(ev("CONTRADICTED"), ev("SUPPORTED"), ev("NEUTRAL"))))
        .isFalse();
  }
}
