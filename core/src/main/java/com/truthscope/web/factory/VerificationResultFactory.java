package com.truthscope.web.factory;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.entity.enums.Verdict;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.EvidenceSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * ClaimVerificationSignal → VerificationResult 엔티티 변환 유틸리티.
 *
 * <p>AnalysisTransactionService 의 buildResult / mapVerdict / mapTier3Reason /
 * isMajorityContradicted / buildReason 을 추출한 정적 팩토리 클래스다.
 *
 * <p>변환 규칙:
 *
 * <ul>
 *   <li>score: Integer(0..100) → Short(0..100) 클램프 변환. null 이면 null 유지.
 *   <li>disclaimer: Tier 2 에만 "AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요." 고정.
 *   <li>verdict: SCORABLE 은 evidence stance 다수결(CONTRADICTED 과반 → CONTRADICTED, 그 외 → SUPPORTED).
 *       비판정 3종은 각각 INSUFFICIENT/TIME_SENSITIVE/OUT_OF_SCOPE 그대로 매핑.
 *   <li>tier3Reason: SCORABLE 이면 null, 비판정 3종은 각각 매핑.
 *   <li>originalResultId: 재검증 시 이전 결과와의 체인 연결용. null 이면 최초 결과를 의미한다.
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VerificationResultFactory {

  /**
   * ClaimVerificationSignal 과 evidence 목록으로 VerificationResult 엔티티를 생성한다.
   *
   * @param signal Tier Cascade 검증 결과 신호
   * @param claim 연결할 Claim 엔티티
   * @param evidence EvidenceSnapshot 목록 (Tier 3 는 빈 리스트)
   * @return 영속 전 VerificationResult 엔티티 (ID 미생성 상태)
   */
  public static VerificationResult buildResult(
      ClaimVerificationSignal signal, Claim claim, List<EvidenceSnapshot> evidence) {
    return buildResult(signal, claim, evidence, null);
  }

  /**
   * ClaimVerificationSignal 과 evidence 목록으로 VerificationResult 엔티티를 생성한다.
   *
   * <p>재검증 경로에서 originalResultId 를 지정할 때 이 메서드를 사용한다.
   *
   * @param signal Tier Cascade 검증 결과 신호
   * @param claim 연결할 Claim 엔티티
   * @param evidence EvidenceSnapshot 목록 (Tier 3 는 빈 리스트)
   * @param originalResultId 재검증 체인의 원본 결과 ID. 최초 결과이면 null.
   * @return 영속 전 VerificationResult 엔티티 (ID 미생성 상태)
   */
  public static VerificationResult buildResult(
      ClaimVerificationSignal signal,
      Claim claim,
      List<EvidenceSnapshot> evidence,
      UUID originalResultId) {
    Short shortScore =
        signal.score() == null ? null : (short) Math.min(100, Math.max(0, signal.score()));
    String disclaimer = signal.tier() == 2 ? "AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요." : null;
    return VerificationResult.builder()
        .claim(claim)
        .tier(signal.tier())
        .score(shortScore)
        .verdict(mapVerdict(signal.status(), evidence))
        .tier3Reason(mapTier3Reason(signal.status()))
        .reason(buildReason(signal))
        .disclaimer(disclaimer)
        .verifiedAt(LocalDateTime.now())
        .originalResultId(originalResultId)
        .build();
  }

  /**
   * ClaimScoreStatus 를 Tier3Reason 으로 매핑한다.
   *
   * <p>SCORABLE(Tier 1/2) 은 tier3_reason = NULL (V6 CHECK 정합).
   *
   * @param status ClaimVerificationSignal 의 status
   * @return Tier3Reason 또는 null (SCORABLE)
   */
  public static Tier3Reason mapTier3Reason(ClaimScoreStatus status) {
    return switch (status) {
      case INSUFFICIENT -> Tier3Reason.INSUFFICIENT;
      case TIME_SENSITIVE -> Tier3Reason.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> Tier3Reason.OUT_OF_SCOPE;
      case SCORABLE -> null;
    };
  }

  /**
   * status 와 evidence stance 를 Verdict 로 매핑한다.
   *
   * <p>SCORABLE 은 evidence 다수결: 반박이 뒷받침보다 많으면 CONTRADICTED, 그 외(동률·evidence 없음 포함)는 SUPPORTED.
   * verdict 컬럼 NOT NULL 이라 모든 status 에 값을 반환한다.
   */
  public static Verdict mapVerdict(ClaimScoreStatus status, List<EvidenceSnapshot> evidence) {
    return switch (status) {
      case SCORABLE -> isMajorityContradicted(evidence) ? Verdict.CONTRADICTED : Verdict.SUPPORTED;
      case INSUFFICIENT -> Verdict.INSUFFICIENT;
      case TIME_SENSITIVE -> Verdict.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> Verdict.OUT_OF_SCOPE;
    };
  }

  /**
   * evidence stance 다수결 — CONTRADICTED 수가 SUPPORTED 수보다 많으면 true (null/빈 리스트는 false).
   *
   * <p>AnalysisTransactionService.isMajorityContradicted 위임 대상 메서드.
   */
  public static boolean isMajorityContradicted(List<EvidenceSnapshot> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return false;
    }
    long contradicted = evidence.stream().filter(e -> "CONTRADICTED".equals(e.stance())).count();
    long supported = evidence.stream().filter(e -> "SUPPORTED".equals(e.stance())).count();
    return contradicted > supported;
  }

  /**
   * VerificationResult.reason (NOT NULL TEXT) 의 v1.x 기본 메시지를 생성한다.
   *
   * <p>Tier 1: 팩트체크 기관 매칭 / Tier 2: 다중 출처 cascade / Tier 3: Validator 미판정 사유.
   */
  public static String buildReason(ClaimVerificationSignal signal) {
    return switch (signal.status()) {
      case SCORABLE -> signal.tier() == 1 ? "Tier 1 팩트체크 기관 매칭 결과" : "Tier 2 다중 출처 cascade 검증 결과";
      case INSUFFICIENT -> "Tier 3 검증 출처 부족";
      case TIME_SENSITIVE -> "Tier 3 시점 의존성으로 검증 보류";
      case OUT_OF_SCOPE -> "Tier 3 검증 범위 외 claim";
    };
  }
}
