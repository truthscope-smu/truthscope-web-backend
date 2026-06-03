package com.truthscope.web.adapter.datasource;

import com.truthscope.web.adapter.WikipediaMetaSource;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Wikipedia REST API v1 Tier 2 보조 meta-source 어댑터.
 *
 * <p>domain-logic.md Wikipedia placement 룰 정합: - Tier 1 evidence로 사용하지 않는다. - 모든 결과에 {@link
 * WikipediaMetaResult#disclaimerRequired()} = true 강제. - {@code factcheck_cache} 저장 금지 —
 * factcheckCacheable = false 강제. - vandalism mitigation: {@link WikipediaRevisionChecker}로 최근 24h
 * 수정 횟수 검증. - 정치/속보 단독 검증 금지 — 본 어댑터는 meta 조회 전용.
 *
 * <p>API 엔드포인트 (key 불필요): - 한국어: {@code https://ko.wikipedia.org/api/rest_v1/page/summary/{title}}
 * - 영어 fallback: {@code https://en.wikipedia.org/api/rest_v1/page/summary/{title}}
 *
 * <p>허용 사용 패턴 (domain-logic.md 인용): - claim 주체(인물/기관)의 메타 정보 조회 - 사건 일자/장소/인물 관계 cross-check -
 * Wikipedia footnote를 원 출처 추적 트리거로 활용
 */
// [Amend 5 — LSP 해결] WikipediaAdapter implements WikipediaMetaSource (DataSourceAdapter 제거)
// WikipediaMetaSource 전용 interface는
// core/src/main/java/com/truthscope/web/adapter/WikipediaMetaSource.java 신규 생성.
// DataSourceAdapter 계약(fetch/parse/fixture)은 Tier 1 evidence 어댑터 전용 — Wikipedia meta-source는 부적합.
// fetch() UnsupportedOperationException override, parse() 빈 리스트 override, fixture() 빈 리스트 override
// 모두 제거.
@Component
@Slf4j
public class WikipediaAdapter implements WikipediaMetaSource {

  private static final String KO_BASE_URL = "https://ko.wikipedia.org";
  private static final String EN_BASE_URL = "https://en.wikipedia.org";
  private static final String SUMMARY_PATH = "/api/rest_v1/page/summary/{title}";
  private static final AdapterMetadata METADATA =
      new AdapterMetadata("Wikipedia", "1.0.0", false, "wikipedia.org");

  private final RestClient.Builder restClientBuilder;
  private final WikipediaRevisionChecker revisionChecker;

  @Value("${truthscope.wikipedia.ko-base-url:" + KO_BASE_URL + "}")
  private String koBaseUrl;

  @Value("${truthscope.wikipedia.en-base-url:" + EN_BASE_URL + "}")
  private String enBaseUrl;

  public WikipediaAdapter(
      RestClient.Builder restClientBuilder, WikipediaRevisionChecker revisionChecker) {
    this.restClientBuilder = restClientBuilder;
    this.revisionChecker = revisionChecker;
  }

  /**
   * [Amend 5] WikipediaMetaSource.fetchMetaSignal() 구현 — WikipediaAdapter 외부 경계 단일 API.
   *
   * <p>한국어 Wikipedia 우선 시도. 응답 부재 시 영어 fallback. vandalism 체크 후 UNSTABLE/UNKNOWN이면 빈 리스트 반환 + 로그.
   * RawResponse가 외부로 노출되지 않아 body() 경로로 extract 접근 불가.
   *
   * @param query keyword = 조회 대상 주체명 (null/blank 금지), lang 무시 (내부에서 ko→en 순 시도)
   * @return List&lt;WikipediaMetaSignal&gt; — Tier 2 신호 리스트 (UNSTABLE/UNKNOWN 시 빈 리스트)
   */
  @Override
  public List<WikipediaMetaSignal> fetchMetaSignal(AdapterQuery query) throws IOException {
    if (query == null) throw new IllegalArgumentException("query는 null 금지");

    String title = query.keyword();
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("query.keyword()는 null/blank 금지");
    }

    // CR review fix: ko 호출이 RestClientException 으로 실패해도 en fallback 시도.
    // 이전 구현은 catch 에서 즉시 빈 리스트 반환 → en fallback 미동작.
    RawResponse raw = null;
    try {
      RawResponse koResponse = fetchSummaryInternal(koBaseUrl, title, "ko");
      if (koResponse.statusCode() == 200 && !koResponse.body().isBlank()) {
        raw = koResponse;
      }
    } catch (RestClientException ex) {
      log.warn(
          "WikipediaAdapter ko 호출 실패 — en fallback 시도. title={} error={}", title, ex.getMessage());
    }

    if (raw == null) {
      try {
        raw = fetchSummaryInternal(enBaseUrl, title, "en");
      } catch (RestClientException ex) {
        log.warn(
            "WikipediaAdapter en fallback 도 실패 — 빈 리스트 반환. title={} error={}",
            title,
            ex.getMessage());
        return List.of();
      }
    }
    // parseAsMetaSignal() 내부에서 vandalism 체크 + WikipediaMetaSignal 생성
    return parseAsMetaSignal(raw);
  }

  // [Amend 5] parse() @Override 제거 — WikipediaMetaSource interface는 parse(RawResponse) 계약 없음.
  // DataSourceAdapter 시절 LSP 우회용 빈 리스트 반환 override 불필요. 제거로 컴파일 오류 없음.

  /**
   * [Amend 5] Wikipedia 전용 파싱 메서드 — WikipediaMetaSignal 리스트 반환 (H1 amend).
   *
   * <p>vandalism 검증 수행 — UNSTABLE/UNKNOWN이면 빈 리스트 반환 + 경고 로그. summary extract 텍스트는 결과에서 제거되며
   * pageUrl + metadata만 WikipediaMetaSignal로 전달. factcheck_cache 저장 경로(DatasourceClaim)로 직접 전달 불가 —
   * 타입 불일치로 컴파일 타임 차단.
   */
  public List<WikipediaMetaSignal> parseAsMetaSignal(RawResponse rawResponse) {
    if (rawResponse == null) throw new IllegalArgumentException("rawResponse는 null 금지");
    if (rawResponse.body().isBlank() || rawResponse.statusCode() != 200) {
      return List.of();
    }
    // JSON 파싱 → WikipediaSummaryPayload → WikipediaMetaResult 변환
    WikipediaSummaryPayload payload = parseJson(rawResponse.body());
    if (payload == null) return List.of();

    VandalismStatus vandalismStatus = revisionChecker.check(payload.title(), payload.lang());
    if (vandalismStatus == VandalismStatus.UNSTABLE) {
      log.warn(
          "WikipediaAdapter: vandalism UNSTABLE 차단 title={} lang={}",
          payload.title(),
          payload.lang());
      return List.of();
    }
    if (vandalismStatus == VandalismStatus.UNKNOWN) {
      log.warn(
          "WikipediaAdapter: vandalism UNKNOWN 보수적 차단 title={} lang={}",
          payload.title(),
          payload.lang());
      return List.of();
    }

    // H1 codex Round 2 amend (옵션 A): extract 필드 제거 — WikipediaMetaResult.of()에서 extract 파라미터 없음.
    // 호출부는 pageUrl + title + description + vandalismStatus 메타만 사용.
    // payload.extract()는 내부적으로 파싱되지만 도메인 record에 보관되지 않음 (lateral reading 원칙 강제).
    WikipediaMetaResult metaResult =
        WikipediaMetaResult.of(
            payload.title(),
            payload.description(),
            payload.contentUrl(),
            payload.lang(),
            vandalismStatus);

    return List.of(new WikipediaMetaSignal(metaResult, Instant.now()));
  }

  @Override
  public HealthStatus health() {
    try {
      // [Amend 5] fetchSummaryInternal 직접 사용 — WikipediaMetaSource.health() 구현
      RestClient client = restClientBuilder.baseUrl(koBaseUrl).build();
      long start = System.currentTimeMillis();
      client.get().uri("/api/rest_v1/page/summary/대한민국").retrieve().toBodilessEntity();
      return HealthStatus.up(System.currentTimeMillis() - start);
    } catch (Exception ex) {
      return HealthStatus.down();
    }
  }

  @Override
  public AdapterMetadata metadata() {
    return METADATA;
  }

  // [Amend 5] fixture() @Override 제거 — WikipediaMetaSource interface는 fixture() 계약 없음.
  // DataSourceAdapter 시절 LSP 우회용 빈 리스트 반환 override 불필요. 제거로 컴파일 오류 없음.

  /**
   * [Amend 5] Wikipedia 전용 fixture — WikipediaMetaSignal 5건 반환. WikipediaMetaSource interface의
   * fixtureAsMetaSignal() 메서드 구현 (재현성 보장용). H1 codex Round 2 amend: WikipediaMetaResult.of()
   * extract 파라미터 제거됨.
   */
  @Override
  public List<WikipediaMetaSignal> fixtureAsMetaSignal() {
    Instant fixedTime = Instant.parse("2026-05-01T00:00:00Z");
    return List.of(
        new WikipediaMetaSignal(
            WikipediaMetaResult.of(
                "대한민국_대통령",
                "대한민국의 대통령",
                "https://ko.wikipedia.org/wiki/대한민국_대통령",
                "ko",
                VandalismStatus.STABLE),
            fixedTime),
        new WikipediaMetaSignal(
            WikipediaMetaResult.of(
                "경제협력개발기구",
                "경제협력개발기구(OECD)",
                "https://ko.wikipedia.org/wiki/경제협력개발기구",
                "ko",
                VandalismStatus.STABLE),
            fixedTime),
        new WikipediaMetaSignal(
            WikipediaMetaResult.of(
                "삼성전자",
                "대한민국의 전자기업",
                "https://ko.wikipedia.org/wiki/삼성전자",
                "ko",
                VandalismStatus.STABLE),
            fixedTime),
        new WikipediaMetaSignal(
            WikipediaMetaResult.of(
                "Korea_Development_Institute",
                "KDI",
                "https://en.wikipedia.org/wiki/Korea_Development_Institute",
                "en",
                VandalismStatus.STABLE),
            fixedTime),
        new WikipediaMetaSignal(
            WikipediaMetaResult.of(
                "한국은행", "한국은행", "https://ko.wikipedia.org/wiki/한국은행", "ko", VandalismStatus.STABLE),
            fixedTime));
  }

  // ── private helpers ──

  /** [H1 codex Round 3 amend] fetchSummary → fetchSummaryInternal 리네이밍. 내부 전용 헬퍼. */
  private RawResponse fetchSummaryInternal(String baseUrl, String title, String lang) {
    RestClient client = restClientBuilder.baseUrl(baseUrl).build();
    ResponseEntity<String> resp =
        client
            .get()
            .uri(SUMMARY_PATH, title)
            .header("Accept", "application/json")
            .header(
                "User-Agent",
                "TruthScope-backend/1.0 (+https://github.com/truthscope-smu/truthscope-web-backend)")
            .retrieve()
            .toEntity(String.class);
    return new RawResponse(
        resp.getBody() != null ? resp.getBody() : "", resp.getStatusCode().value(), "JSON");
  }

  private WikipediaSummaryPayload parseJson(String body) {
    // Jackson ObjectMapper 사용 — com.fasterxml.jackson.databind.ObjectMapper
    // 필드 매핑: title, extract, description, content_urls.desktop.page
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
      String title = node.path("title").asText("");
      String extract = node.path("extract").asText("");
      String description = node.path("description").asText("");
      String contentUrl = node.path("content_urls").path("desktop").path("page").asText("");
      String lang = node.path("lang").asText("ko");
      if (title.isBlank()) return null;
      return new WikipediaSummaryPayload(title, extract, description, contentUrl, lang);
    } catch (Exception ex) {
      log.warn("WikipediaAdapter.parseJson 실패 error={}", ex.getMessage());
      return null;
    }
  }

  /** Wikipedia REST API summary 응답 내부 DTO (파싱용 private record). */
  private record WikipediaSummaryPayload(
      String title, String extract, String description, String contentUrl, String lang) {}
}
