package com.truthscope.web.scoring;

import java.util.List;

/**
 * Wave 2 cascade 가 받는 입력 contract. Wave 1 ClaimAnalysisService 가 구현.
 *
 * <p>구현체는 기사 본문({@code articleBody}) 을 분석하여 {@link ClaimDraft} 목록을 반환한다. 반환 목록이 비어 있으면 기사에서 검증 가능한
 * claim 이 추출되지 않은 것이다.
 */
public interface ClaimAnalysisPort {

  /**
   * 기사 본문을 분석하여 claim 후보 목록을 반환한다.
   *
   * @param articleBody 분석 대상 기사 본문 텍스트 (non-null)
   * @return 추출된 {@link ClaimDraft} 목록 (비어 있을 수 있음)
   */
  List<ClaimDraft> analyze(String articleBody);
}
