package com.truthscope.web.converter;

import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import com.truthscope.web.scoring.EvidenceSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * EvidenceSnapshot → VerifySource 엔티티 변환 유틸리티 (T7 converter).
 *
 * <p>stance 매핑 (DB CHECK 제약 지원값: supports/refutes/neutral):
 *
 * <ul>
 *   <li>SUPPORTED → supports
 *   <li>CONTRADICTED → refutes
 *   <li>NEUTRAL → neutral
 *   <li>UNRELATED → 제외 (저장 안 함)
 * </ul>
 *
 * <p>summary: matchedFields 맵에서 값들을 공백으로 연결한 문자열. 비어 있으면 null.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VerifySourceConverter {

  /**
   * EvidenceSnapshot 목록을 VerifySource 엔티티 목록으로 변환한다.
   *
   * <p>UNRELATED stance 는 필터링하여 제외한다.
   *
   * @param result 연결할 VerificationResult 엔티티
   * @param evidence EvidenceSnapshot 목록
   * @return VerifySource 엔티티 목록 (UNRELATED 제외)
   */
  public static List<VerifySource> toEntities(
      VerificationResult result, List<EvidenceSnapshot> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return List.of();
    }
    return evidence.stream()
        .filter(snap -> mapStance(snap.stance()) != null)
        .map(snap -> toEntity(result, snap))
        .collect(Collectors.toList());
  }

  private static VerifySource toEntity(VerificationResult result, EvidenceSnapshot snap) {
    return VerifySource.builder()
        .result(result)
        .title(snap.title())
        .publisher(snap.publisher())
        .url(snap.url())
        .stance(mapStance(snap.stance()))
        .summary(buildSummary(snap.matchedFields()))
        .build();
  }

  /** EvidenceSnapshot.stance (대문자) → DB 소문자 매핑. UNRELATED 는 null 반환 (저장 제외 신호). */
  private static String mapStance(String stance) {
    if (stance == null) {
      return null;
    }
    return switch (stance.toUpperCase(Locale.ROOT)) {
      case "SUPPORTED" -> "supports";
      case "CONTRADICTED" -> "refutes";
      case "NEUTRAL" -> "neutral";
      default -> null; // UNRELATED 또는 미정의 → 저장 제외
    };
  }

  /** matchedFields 맵 값들을 공백 연결. 비어 있으면 null. */
  private static String buildSummary(Map<String, String> matchedFields) {
    if (matchedFields == null || matchedFields.isEmpty()) {
      return null;
    }
    String joined = String.join(" ", matchedFields.values());
    return joined.isBlank() ? null : joined;
  }
}
