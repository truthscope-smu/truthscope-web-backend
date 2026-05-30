package com.truthscope.web.service.fidelity;

import com.truthscope.web.scoring.EvidenceCandidate;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.FidelityClassifierPort;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * FidelityClassifierPort 의 local/test profile 결정적 stub.
 *
 * <p>Production 환경에서는 {@link FidelityClassifierService} 가 활성 (@Profile("production")). Local/test
 * 환경 에서는 본 stub 이 활성 — ApplicationContext bean 부재 차단. Gemini 실호출 없이 결정적 stance 반환.
 *
 * <p>결정 규칙: 후보의 title 또는 body 에 claim 핵심 키워드(숫자·연도·한국어 명사)가 포함되면 SUPPORTED + 비어 있지 않은 matchedFields
 * 반환. 관련성 없는 후보는 제외 — codex Round 2 조건 1(관련성 필터) 정합.
 */
@Service
@Profile("!production")
public class FidelityClassifierStubService implements FidelityClassifierPort {

  @Override
  public List<EvidenceSnapshot> classify(
      String claimText, List<EvidenceCandidate> candidates, @Nullable String userApiKey) {
    if (claimText == null || claimText.isBlank() || candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    List<String> keywords = extractKeywords(claimText);
    List<EvidenceSnapshot> results = new ArrayList<>();

    for (EvidenceCandidate candidate : candidates) {
      if (candidateMatchesClaim(candidate, keywords)) {
        results.add(
            new EvidenceSnapshot(
                candidate.url(),
                candidate.publisher(),
                candidate.title(),
                "SUPPORTED",
                Map.of("제도명", candidate.title() != null ? candidate.title() : "정책")));
      }
      // 관련성 없는 후보는 제외 — NEUTRAL/0-match 반환 금지 (codex Round 2 조건 1 정합)
    }

    return List.copyOf(results);
  }

  /**
   * claim 텍스트에서 핵심 키워드를 추출한다 (숫자·연도·길이 2 이상 한국어 토큰).
   *
   * @param claimText claim 텍스트
   * @return 핵심 키워드 목록
   */
  private List<String> extractKeywords(String claimText) {
    List<String> keywords = new ArrayList<>();
    // 숫자 토큰 (연도, 수치, 금액 등)
    java.util.regex.Matcher numMatcher =
        java.util.regex.Pattern.compile("[0-9]+[.%억만원%]*").matcher(claimText);
    while (numMatcher.find()) {
      String tok = numMatcher.group();
      if (!tok.isBlank()) {
        keywords.add(tok);
      }
    }
    // 한글 토큰 (2자 이상)
    java.util.regex.Matcher korMatcher =
        java.util.regex.Pattern.compile("[가-힣]{2,}").matcher(claimText);
    while (korMatcher.find()) {
      keywords.add(korMatcher.group());
    }
    return keywords;
  }

  /**
   * 후보 title/body 에 키워드가 하나라도 포함되면 관련성 있음으로 판정한다.
   *
   * @param candidate 후보
   * @param keywords 핵심 키워드 목록
   * @return 관련성 여부
   */
  private boolean candidateMatchesClaim(EvidenceCandidate candidate, List<String> keywords) {
    if (keywords.isEmpty()) {
      return false;
    }
    String haystack =
        ((candidate.title() != null ? candidate.title() : "")
                + " "
                + (candidate.body() != null ? candidate.body() : ""))
            .toLowerCase();
    return keywords.stream().anyMatch(kw -> haystack.contains(kw.toLowerCase()));
  }
}
