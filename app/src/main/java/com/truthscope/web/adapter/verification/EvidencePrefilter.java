package com.truthscope.web.adapter.verification;

import com.truthscope.web.adapter.datasource.DataGoKrPolicyItem;
import com.truthscope.web.scoring.EvidenceCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * claimText 핵심어 기반 lexical prefilter — DataGoKrPolicyItem → EvidenceCandidate top-N (T4 helper).
 *
 * <p>ArchUnit serviceNaming: service.* 하위가 아닌 adapter.verification 패키지. @Component.
 *
 * <p>핵심어 추출: 공백 토큰화 후 길이 2 이상 토큰 (숫자/기관명/정책명 포함). 각 아이템의 title+body 에서 핵심어 출현 횟수로 정렬, url dedupe,
 * top n 반환.
 */
@Component
public class EvidencePrefilter {

  /**
   * claimText 핵심어로 items 를 점수화하여 top n EvidenceCandidate 를 반환한다.
   *
   * @param claimText 검증 대상 claim 텍스트
   * @param items DataGoKrAdapter 가 수집한 정책뉴스/보도자료 목록
   * @param n 반환할 최대 후보 수
   * @return 핵심어 관련성 기준 상위 n개 EvidenceCandidate (url 기준 dedupe)
   */
  public List<EvidenceCandidate> top(String claimText, List<DataGoKrPolicyItem> items, int n) {
    if (items == null || items.isEmpty() || claimText == null || claimText.isBlank()) {
      return List.of();
    }

    List<String> keywords = extractKeywords(claimText);
    if (keywords.isEmpty()) {
      // 키워드 없으면 순서 그대로 top n 반환 (url dedupe만)
      Map<String, DataGoKrPolicyItem> dedupe = new LinkedHashMap<>();
      for (DataGoKrPolicyItem item : items) {
        if (item.url() != null && !item.url().isBlank()) {
          dedupe.putIfAbsent(item.url(), item);
        }
      }
      return dedupe.values().stream().limit(n).map(this::toCandidate).collect(Collectors.toList());
    }

    // url dedupe + score 계산
    Map<String, DataGoKrPolicyItem> dedupe = new LinkedHashMap<>();
    for (DataGoKrPolicyItem item : items) {
      if (item.url() != null && !item.url().isBlank()) {
        dedupe.putIfAbsent(item.url(), item);
      }
    }

    List<ScoredItem> scored = new ArrayList<>();
    for (DataGoKrPolicyItem item : dedupe.values()) {
      int score = score(item, keywords);
      if (score > 0) {
        scored.add(new ScoredItem(item, score));
      }
    }

    scored.sort((a, b) -> Integer.compare(b.score, a.score));

    return scored.stream().limit(n).map(si -> toCandidate(si.item)).collect(Collectors.toList());
  }

  private List<String> extractKeywords(String claimText) {
    return Arrays.stream(claimText.split("\\s+"))
        .filter(token -> token.length() >= 2)
        .collect(Collectors.toList());
  }

  private int score(DataGoKrPolicyItem item, List<String> keywords) {
    String haystack = buildHaystack(item);
    int count = 0;
    for (String kw : keywords) {
      int idx = 0;
      while ((idx = haystack.indexOf(kw, idx)) >= 0) {
        count++;
        idx += kw.length();
      }
    }
    return count;
  }

  private String buildHaystack(DataGoKrPolicyItem item) {
    StringBuilder sb = new StringBuilder();
    if (item.title() != null) {
      sb.append(item.title());
      sb.append(' ');
    }
    if (item.body() != null) {
      sb.append(item.body());
    }
    return sb.toString();
  }

  private EvidenceCandidate toCandidate(DataGoKrPolicyItem item) {
    return new EvidenceCandidate(item.url(), item.publisher(), item.title(), item.body());
  }

  private static final class ScoredItem {
    final DataGoKrPolicyItem item;
    final int score;

    ScoredItem(DataGoKrPolicyItem item, int score) {
      this.item = item;
      this.score = score;
    }
  }
}
