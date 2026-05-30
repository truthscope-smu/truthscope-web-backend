package com.truthscope.web.scoring;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * 충실성 분류기 포트 — Gemini 기반 공식 원문 충실성 판정 (production) 과 결정적 stub (local/test) 의 공통 계약.
 *
 * <p>반환값: stance SUPPORTED 또는 CONTRADICTED 이고 matchedFields 비어 있지 않은 후보만 포함 (codex Round 2 조건 1 —
 * NEUTRAL 또는 0-match 후보 제외 → PolicyEvidenceScorer 의 0점 SCORABLE 차단).
 */
public interface FidelityClassifierPort {

  /**
   * claimText 와 후보 목록을 비교하여 관련성이 있는 {@link EvidenceSnapshot} 목록을 반환한다.
   *
   * @param claimText 검증 대상 claim 텍스트
   * @param candidates prefilter 를 거친 후보 목록
   * @param userApiKey BYOK 사용자 API 키 (null 이면 서버 기본 키 사용)
   * @return stance SUPPORTED 또는 CONTRADICTED 이고 matchedFields 비어 있지 않은 후보 목록. 실패 시 빈 목록.
   */
  List<EvidenceSnapshot> classify(
      String claimText, List<EvidenceCandidate> candidates, @Nullable String userApiKey);
}
