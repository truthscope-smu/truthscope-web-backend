package com.truthscope.web.adapter.datasource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Wikipedia 문서 vandalism 안정성 검증기.
 *
 * <p>domain-logic.md mitigation 정합: - 최근 24h 수정 횟수 5회 이하 → {@link VandalismStatus#STABLE} - 6회 이상 →
 * {@link VandalismStatus#UNSTABLE} - revision API 호출 실패 → {@link VandalismStatus#UNKNOWN} (보수적 차단)
 *
 * <p>MediaWiki Action API: {@code
 * /w/api.php?action=query&prop=revisions&rvlimit=5&rvprop=timestamp|ids&titles={title}}
 *
 * <p>네이밍: "Checker" 접미사 — ArchUnit serviceNaming 룰 (..service.. Service 접미사 의무)과 충돌하지 않도록
 * adapter.datasource 패키지에 위치. @Component이지만 Service 패키지 아님.
 *
 * <p>H4 codex Round 2 미해소 amend: {@link java.time.Clock} 필드를 생성자 주입으로 전환. production 코드는
 * {@code @Bean public Clock systemClock()} 으로 {@code Clock.systemUTC()} 주입. WireMock 통합 테스트는
 * {@code @TestConfiguration}에서 {@code Clock.fixed()} 주입. private {@code
 * countRecentRevisions(String)} 오버로드 제거 — 모든 경로가 주입된 clock 사용.
 */
@Component
@Slf4j
public class WikipediaRevisionChecker {

  /** 최근 24h 수정 횟수 임계값 — 이하이면 STABLE. */
  static final int VANDALISM_THRESHOLD = 5;

  private final RestClient.Builder restClientBuilder;

  /** H4 codex Round 2 amend: Clock 필드 주입 — Instant.now() 직접 호출 금지. */
  private final java.time.Clock clock;

  @Value("${truthscope.wikipedia.ko-base-url:https://ko.wikipedia.org}")
  private String koBaseUrl;

  @Value("${truthscope.wikipedia.en-base-url:https://en.wikipedia.org}")
  private String enBaseUrl;

  /**
   * H4 codex Round 2 amend: Clock 주입 생성자. @RequiredArgsConstructor 대신 명시 생성자. GeminiClient.java 라인
   * 46 패턴 정합 — Qualifier 필요 시 명시 생성자 의무.
   */
  public WikipediaRevisionChecker(RestClient.Builder restClientBuilder, java.time.Clock clock) {
    this.restClientBuilder = restClientBuilder;
    this.clock = clock;
  }

  /** 단위 테스트용 편의 생성자 — Clock.systemUTC() 기본값. */
  WikipediaRevisionChecker(RestClient.Builder restClientBuilder) {
    this(restClientBuilder, java.time.Clock.systemUTC());
  }

  /**
   * 주어진 Wikipedia 문서의 최근 24h 수정 횟수로 vandalism 안정성 판정.
   *
   * @param title Wikipedia 문서 제목
   * @param lang "ko" 또는 "en"
   * @return VandalismStatus — STABLE/UNSTABLE/UNKNOWN
   */
  public VandalismStatus check(String title, String lang) {
    if (title == null || title.isBlank()) return VandalismStatus.UNKNOWN;
    String baseUrl = "en".equals(lang) ? enBaseUrl : koBaseUrl;
    try {
      RestClient client = restClientBuilder.baseUrl(baseUrl).build();
      String response =
          client
              .get()
              .uri(
                  "/w/api.php?action=query&prop=revisions&rvlimit=10&rvprop=timestamp"
                      + "&rvdir=older&titles={title}&format=json",
                  title)
              .header("User-Agent", "TruthScope/1.0 (gwonseok02@gmail.com)")
              .retrieve()
              .body(String.class);
      // H4 codex Round 2 amend: this.clock 사용 (주입된 clock — Clock.systemUTC() 또는 Clock.fixed())
      int recentCount = countRecentRevisions(response, this.clock);
      // H2 amend: fail-closed 가드 — countRecentRevisions() 실패 시 -1 반환.
      // 가드 없으면 (-1 <= VANDALISM_THRESHOLD=5)가 참이 되어 STABLE 오분류 발생.
      // malformed JSON / 빈 body에서도 UNKNOWN으로 차단 (fail-closed 원칙).
      if (recentCount < 0) {
        log.warn(
            "WikipediaRevisionChecker: title={} lang={} recentCount=-1 → UNKNOWN (fail-closed)",
            title,
            lang);
        return VandalismStatus.UNKNOWN;
      }
      VandalismStatus status =
          recentCount <= VANDALISM_THRESHOLD ? VandalismStatus.STABLE : VandalismStatus.UNSTABLE;
      if (status == VandalismStatus.UNSTABLE) {
        log.warn(
            "WikipediaRevisionChecker: title={} lang={} recentRevisions={} → UNSTABLE",
            title,
            lang,
            recentCount);
      }
      return status;
    } catch (RestClientException ex) {
      log.warn(
          "WikipediaRevisionChecker.check 실패 title={} lang={} error={}",
          title,
          lang,
          ex.getMessage());
      return VandalismStatus.UNKNOWN;
    }
  }

  /**
   * MediaWiki revisions 응답 JSON에서 최근 24h 이내 revision 수 카운트. Jackson ObjectMapper로 직접 파싱. 실패 시 -1 반환
   * → UNKNOWN 처리.
   *
   * <p>H4 amend: cutoff 계산에 {@link java.time.Clock} 파라미터 주입. 테스트에서 {@code Clock.fixed()} 사용으로
   * cassette timestamp 만료 문제 차단. 프로덕션에서는 {@code Clock.systemUTC()} 사용.
   *
   * @param responseBody MediaWiki revisions JSON 응답 body
   * @param clock cutoff 기준 시계 (테스트에서 Clock.fixed()로 고정)
   */
  int countRecentRevisions(String responseBody, java.time.Clock clock) {
    if (responseBody == null || responseBody.isBlank()) return -1;
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
      com.fasterxml.jackson.databind.JsonNode pages = root.path("query").path("pages");
      if (pages.isMissingNode()) return -1;
      // pages는 pageId → page 객체 map
      com.fasterxml.jackson.databind.JsonNode page = pages.fields().next().getValue();
      com.fasterxml.jackson.databind.JsonNode revisions = page.path("revisions");
      if (!revisions.isArray()) return 0;
      // H4 amend: Clock 파라미터로 cutoff 계산 — Instant.now() 직접 호출 금지
      java.time.Instant cutoff =
          java.time.Instant.now(clock).minus(24, java.time.temporal.ChronoUnit.HOURS);
      int count = 0;
      for (com.fasterxml.jackson.databind.JsonNode rev : revisions) {
        String ts = rev.path("timestamp").asText("");
        if (!ts.isBlank()) {
          java.time.Instant revTime = java.time.Instant.parse(ts);
          if (revTime.isAfter(cutoff)) count++;
        }
      }
      return count;
    } catch (Exception ex) {
      log.warn("WikipediaRevisionChecker.countRecentRevisions 파싱 실패 error={}", ex.getMessage());
      return -1;
    }
  }

  // H4 codex Round 2 amend: private countRecentRevisions(String) 오버로드 제거.
  // 모든 경로가 countRecentRevisions(String, Clock) + this.clock을 사용.
  // production은 systemClock @Bean 주입, 테스트는 Clock.fixed() @TestConfiguration 주입.
}
