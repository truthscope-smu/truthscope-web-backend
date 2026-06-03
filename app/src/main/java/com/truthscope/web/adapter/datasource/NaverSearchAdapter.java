package com.truthscope.web.adapter.datasource;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Naver News Search API 어댑터 (Phase 72 보조 Tier-1 출처).
 *
 * <p>plain @Component, @Transactional 없음 (RC-01). 실패 시 빈 리스트 반환 (Tier 3 안전강하). 인증키:
 * truthscope.datasource.naver-client-id / naver-client-secret (application-local.yml 주입). 빈 키이면 API
 * 호출 건너뜀.
 *
 * <p>날짜 처리 주의: Naver API 는 retrieve() 의 날짜 윈도우 밖 post-retrieval 병합 경로이므로 window 미적용이 아키텍처상 정상이다.
 * pubDate(RFC-822)를 파싱하여 LocalDateTime 으로 변환하고, 파싱 실패 시 now() 폴백을 사용한다. HybridCascadeService 의
 * EvidenceWindowResolver 날짜 비교는 data.go.kr 경로만 적용됨을 주의할 것.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NaverSearchAdapter {

  private final RestClient.Builder restClientBuilder;

  @Value("${truthscope.datasource.naver-client-id:}")
  private String clientId;

  @Value("${truthscope.datasource.naver-client-secret:}")
  private String clientSecret;

  @Value("${truthscope.datasource.naver-base-url:https://openapi.naver.com}")
  private String naverBaseUrl;

  // RFC-822 날짜 형식 (예: "Mon, 02 Jun 2025 14:30:00 +0900")
  private static final DateTimeFormatter RFC_822_FORMATTER =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH);

  /**
   * claimText 를 Naver 뉴스 검색 쿼리로 사용하여 결과를 DataGoKrPolicyItem 목록으로 반환한다. 인증키 미설정 시 빈 리스트 반환.
   *
   * @param claimText 검증 대상 claim 텍스트
   * @return DataGoKrPolicyItem 목록 (최대 10건), 실패 시 빈 리스트
   */
  public List<DataGoKrPolicyItem> search(String claimText) {
    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
      log.debug("NaverSearchAdapter: 인증키 미설정, 건너뜀.");
      return List.of();
    }
    try {
      RestClient client = restClientBuilder.baseUrl(naverBaseUrl).build();
      // Naver API 는 JSON 응답
      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          client
              .get()
              .uri("/v1/search/news.json?query={q}&display=10&sort=date", claimText)
              .header("X-Naver-Client-Id", clientId)
              .header("X-Naver-Client-Secret", clientSecret)
              .retrieve()
              .body(Map.class);

      if (response == null) return List.of();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
      if (items == null || items.isEmpty()) return List.of();

      List<DataGoKrPolicyItem> result = new ArrayList<>();
      for (Map<String, Object> item : items) {
        String link = (String) item.get("link");
        if (link == null || link.isBlank()) continue;
        String title = stripHtmlTags((String) item.get("title"));
        String description = stripHtmlTags((String) item.get("description"));
        String pubDateStr = (String) item.get("pubDate");
        LocalDateTime approveDate = parsePubDate(pubDateStr);
        result.add(new DataGoKrPolicyItem(link, "Naver", title, description, approveDate));
      }
      return result;

    } catch (Exception ex) {
      log.warn("NaverSearchAdapter.search: 예외 발생, 빈 리스트 반환. error={}", ex.getMessage());
      return List.of();
    }
  }

  /**
   * RFC-822 날짜 문자열을 LocalDateTime 으로 변환한다. 파싱 실패 시 now() 폴백.
   *
   * @param pubDateStr Naver API 의 pubDate 필드 값 (RFC-822 형식)
   * @return 파싱된 LocalDateTime, 실패 시 LocalDateTime.now()
   */
  private LocalDateTime parsePubDate(String pubDateStr) {
    if (pubDateStr == null || pubDateStr.isBlank()) {
      return LocalDateTime.now();
    }
    try {
      ZonedDateTime zdt = ZonedDateTime.parse(pubDateStr, RFC_822_FORMATTER);
      return zdt.toLocalDateTime();
    } catch (DateTimeParseException e) {
      log.debug(
          "NaverSearchAdapter: pubDate 파싱 실패 '{}', now() 폴백. cause={}", pubDateStr, e.getMessage());
      return LocalDateTime.now();
    }
  }

  private String stripHtmlTags(String input) {
    if (input == null) return "";
    return input.replaceAll("<[^>]+>", "");
  }
}
