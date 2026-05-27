package com.truthscope.web.scoring;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Wave 2 cascade 가 받는 입력 contract. Wave 1 ClaimAnalysisService 가 구현.
 *
 * <p>구현체는 기사 본문({@code articleBody}) 을 분석하여 {@link ClaimDraft} 목록을 반환한다. 반환 목록이 비어 있으면 기사에서 검증 가능한
 * claim 이 추출되지 않은 것이다.
 *
 * <p>BE #74 amend: BYOK 지원 — {@code userApiKey} 인자가 추가된 2-arg 메서드가 정본. 기존 1-arg 시그니처는 default 메서드로
 * backward compat 유지.
 */
public interface ClaimAnalysisPort {

  /**
   * 기사 본문을 분석하여 claim 후보 목록을 반환한다.
   *
   * @param articleBody 분석 대상 기사 본문 텍스트 (non-null)
   * @param userApiKey BYOK 사용자 Gemini API 키 (null → 서버 기본 키 사용)
   * @return 추출된 {@link ClaimDraft} 목록 (비어 있을 수 있음)
   */
  List<ClaimDraft> analyze(String articleBody, @Nullable String userApiKey);

  /**
   * Backward-compat: BYOK 없는 호출 — {@code userApiKey} null 위임.
   *
   * @param articleBody 분석 대상 기사 본문 텍스트
   * @return 추출된 {@link ClaimDraft} 목록
   */
  default List<ClaimDraft> analyze(String articleBody) {
    return analyze(articleBody, null);
  }
}
