package com.truthscope.web.service.claim;

import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ClaimAnalysisPort 의 local/test profile stub.
 *
 * <p>Production 환경에서는 ClaimAnalysisService 가 활성 (@Profile("production")). Local/test 환경
 * (test/default profile) 에서는 본 stub 이 활성 — ApplicationContext bean 부재 차단 (rev.4 amend Round 3 CX-6
 * 더하기 R1-1).
 */
@Service
@Profile("!production")
public class ClaimAnalysisStubService implements ClaimAnalysisPort {

  private static final List<ClaimDraft> FIXTURE =
      List.of(
          new ClaimDraft(
              UUID.randomUUID(),
              "정부는 2026년 경제성장률 전망치를 2.1%로 발표했다.",
              "정부",
              true,
              "원본 문맥",
              ClaimStatusCandidate.SCORABLE,
              null),
          new ClaimDraft(
              UUID.randomUUID(),
              "전문가들은 수출 회복세가 주요 요인이라고 분석했다.",
              "전문가",
              false,
              "원본 문맥",
              ClaimStatusCandidate.SCORABLE,
              null),
          new ClaimDraft(
              UUID.randomUUID(),
              "일부 학계는 글로벌 금리 인하 가능성을 변수로 지적했다.",
              "학계",
              false,
              "원본 문맥",
              ClaimStatusCandidate.INSUFFICIENT_CANDIDATE,
              null));

  @Override
  @Transactional(readOnly = true)
  public List<ClaimDraft> analyze(String articleBody, @Nullable String userApiKey) {
    // userApiKey 무시 — stub은 fixture 반환
    if (articleBody == null || articleBody.isBlank()) {
      return List.of();
    }
    return FIXTURE;
  }
}
