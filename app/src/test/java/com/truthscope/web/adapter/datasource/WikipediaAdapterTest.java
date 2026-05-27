package com.truthscope.web.adapter.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("WikipediaAdapter")
class WikipediaAdapterTest {

  private WikipediaRevisionChecker revisionChecker;
  private WikipediaAdapter adapter;

  @BeforeEach
  void setUp() {
    revisionChecker = mock(WikipediaRevisionChecker.class);
    adapter = new WikipediaAdapter(RestClient.builder(), revisionChecker);
  }

  @Nested
  @DisplayName("parse — Tier 1 가드")
  class Tier1Guard {

    @Test
    @DisplayName("WikipediaMetaResult.tier는 반드시 2이어야 한다 — 1 지정 시 IllegalArgumentException")
    void tier1Forbidden() {
      // H1 codex Round 2 amend: extract 필드 제거됨 — 파라미터 목록에서 extract 없음
      assertThatThrownBy(
              () ->
                  new WikipediaMetaResult(
                      "제목",
                      "설명",
                      "https://example.com",
                      "ko",
                      VandalismStatus.STABLE,
                      (short) 1,
                      true,
                      false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tier는 반드시 2");
    }

    @Test
    @DisplayName("WikipediaMetaResult.disclaimerRequired=false 금지")
    void disclaimerRequiredFalseForMidden() {
      // H1 codex Round 2 amend: extract 필드 제거됨
      assertThatThrownBy(
              () ->
                  new WikipediaMetaResult(
                      "제목",
                      "설명",
                      "https://example.com",
                      "ko",
                      VandalismStatus.STABLE,
                      (short) 2,
                      false,
                      false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("disclaimerRequired");
    }

    @Test
    @DisplayName("WikipediaMetaResult.factcheckCacheable=true 금지 — factcheck_cache 저장 방지")
    void factcheckCacheableTrue_forbidden() {
      // H1 codex Round 2 amend: extract 필드 제거됨
      assertThatThrownBy(
              () ->
                  new WikipediaMetaResult(
                      "제목",
                      "설명",
                      "https://example.com",
                      "ko",
                      VandalismStatus.STABLE,
                      (short) 2,
                      true,
                      true))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("factcheckCacheable");
    }

    @Test
    @DisplayName(
        "WikipediaMetaResult.of() 정적 팩토리는 tier=2/disclaimerRequired=true/factcheckCacheable=false 강제")
    void staticFactory_enforcesTier2() {
      // H1 codex Round 2 amend: extract 파라미터 제거됨 — description만 전달
      WikipediaMetaResult result =
          WikipediaMetaResult.of(
              "제목", "설명", "https://ko.wikipedia.org/wiki/제목", "ko", VandalismStatus.STABLE);
      assertThat(result.tier()).isEqualTo((short) 2);
      assertThat(result.disclaimerRequired()).isTrue();
      assertThat(result.factcheckCacheable()).isFalse();
      // extract 필드가 존재하지 않음 — result.extract() 호출 자체가 컴파일 에러 (lateral reading 원칙)
    }
  }

  // [Amend 5] fetch() @Override 자체가 제거됨 — WikipediaMetaSource interface는 fetch() 계약 없음.
  // 이전 FetchSealed 중첩 클래스(fetch_throws_UnsupportedOperationException)는 더 이상 유효하지 않으므로 제거.
  // 대신 fetchMetaSignal() 직접 호출 경로가 단일 외부 API임을 아래 통합 테스트 S1/S2에서 검증.
  // [Amend 5 Nested 테스트 대체] WikipediaMetaSource 타입으로 선언 가능 검증 (컴파일 타임 LSP 정합)
  @Nested
  @DisplayName("WikipediaMetaSource 계약 — Amend 5 LSP 해결")
  class WikipediaMetaSourceContract {

    @Test
    @DisplayName("WikipediaAdapter가 WikipediaMetaSource 타입으로 참조 가능 — LSP 정합 (Amend 5)")
    void adapter_isAssignableAs_WikipediaMetaSource() {
      // WikipediaAdapter implements WikipediaMetaSource 전환 후 타입 정합 컴파일 타임 검증
      // WikipediaMetaSource 타입으로 선언 가능 → DataSourceAdapter 계약과 분리됨을 확인
      assertThat(adapter).isInstanceOf(com.truthscope.web.adapter.WikipediaMetaSource.class);
    }

    @Test
    @DisplayName("WikipediaAdapter는 DataSourceAdapter 타입이 아님 — LSP 위반 경로 제거 (Amend 5)")
    void adapter_isNot_DataSourceAdapter() {
      // implements DataSourceAdapter 제거 후 타입 불일치 확인 → UnsupportedOperationException 봉인 패턴 불필요
      assertThat(adapter)
          .isNotInstanceOf(com.truthscope.web.adapter.datasource.DataSourceAdapter.class);
    }
  }

  @Nested
  @DisplayName("parse — vandalism mitigation")
  class VandalismMitigation {

    @Test
    @DisplayName("UNSTABLE 문서 parseAsMetaSignal → 빈 리스트 반환 (차단)")
    void unstableDocument_returnsEmpty() {
      when(revisionChecker.check(anyString(), anyString())).thenReturn(VandalismStatus.UNSTABLE);
      // JSON stub body with title/extract/description/lang
      String body =
          "{\"title\":\"테스트\",\"extract\":\"내용\","
              + "\"description\":\"설명\",\"lang\":\"ko\","
              + "\"content_urls\":{\"desktop\":{\"page\":\"https://ko.wikipedia.org/wiki/테스트\"}}}";
      RawResponse raw = new RawResponse(body, 200, "JSON");

      List<WikipediaMetaSignal> result = adapter.parseAsMetaSignal(raw);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("UNKNOWN 문서 parseAsMetaSignal → 빈 리스트 반환 (보수적 차단)")
    void unknownDocument_returnsEmpty() {
      when(revisionChecker.check(anyString(), anyString())).thenReturn(VandalismStatus.UNKNOWN);
      String body =
          "{\"title\":\"테스트\",\"extract\":\"내용\","
              + "\"description\":\"설명\",\"lang\":\"ko\","
              + "\"content_urls\":{\"desktop\":{\"page\":\"https://ko.wikipedia.org/wiki/테스트\"}}}";
      RawResponse raw = new RawResponse(body, 200, "JSON");

      List<WikipediaMetaSignal> result = adapter.parseAsMetaSignal(raw);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
        "STABLE 문서 parseAsMetaSignal → WikipediaMetaSignal 1건 반환 + extract 필드 부재 확인 (H1 amend + codex Round 2)")
    void stableDocument_returnsMetaSignal() {
      when(revisionChecker.check(anyString(), anyString())).thenReturn(VandalismStatus.STABLE);
      String body =
          "{\"title\":\"삼성전자\",\"extract\":\"대한민국의 전자기업\","
              + "\"description\":\"대한민국 기업\",\"lang\":\"ko\","
              + "\"content_urls\":{\"desktop\":{\"page\":\"https://ko.wikipedia.org/wiki/삼성전자\"}}}";
      RawResponse raw = new RawResponse(body, 200, "JSON");

      List<WikipediaMetaSignal> result = adapter.parseAsMetaSignal(raw);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).metaResult().tier()).isEqualTo((short) 2);
      assertThat(result.get(0).metaResult().pageUrl()).contains("wikipedia.org");
      // H1 codex Round 2 amend: extract 필드 자체가 WikipediaMetaResult에 존재하지 않음.
      // result.get(0).metaResult().extract() 호출 시 컴파일 에러 — 접근 경로 완전 차단.
      // description 필드는 존재: "대한민국 기업"
      assertThat(result.get(0).metaResult().description()).isEqualTo("대한민국 기업");
    }

    @Test
    @DisplayName(
        "parseAsMetaSignal only path — DatasourceClaim 경로 차단 (Amend 5: parse() override 제거, DataSourceAdapter 미구현)")
    void parseAsMetaSignal_isDatasourceClaimFreeByType() {
      when(revisionChecker.check(anyString(), anyString())).thenReturn(VandalismStatus.STABLE);
      String body =
          "{\"title\":\"삼성전자\",\"extract\":\"내용\","
              + "\"description\":\"설명\",\"lang\":\"ko\","
              + "\"content_urls\":{\"desktop\":{\"page\":\"https://ko.wikipedia.org/wiki/삼성전자\"}}}";
      RawResponse raw = new RawResponse(body, 200, "JSON");

      // WikipediaAdapter does not implement DataSourceAdapter.parse(RawResponse) — Amend 5 LSP 해결.
      // WikipediaMetaSource interface has no parse() contract.
      // Only path is parseAsMetaSignal() which returns WikipediaMetaSignal (Tier 2).
      // DatasourceClaim(Tier 1) path is absent — compile time enforced by type mismatch.
      List<WikipediaMetaSignal> result = adapter.parseAsMetaSignal(raw);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).metaResult().factcheckCacheable()).isFalse();
    }
  }

  @Nested
  @DisplayName("parse — 예외 경로")
  class ParseEdge {

    @Test
    @DisplayName("null rawResponse → parseAsMetaSignal IllegalArgumentException (H1 amend)")
    void nullRawResponse() {
      assertThatThrownBy(() -> adapter.parseAsMetaSignal(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null 금지");
    }

    @Test
    @DisplayName("empty body → parseAsMetaSignal empty list (H1 amend)")
    void emptyBody_returnsEmpty() {
      List<WikipediaMetaSignal> result =
          adapter.parseAsMetaSignal(new RawResponse("", 200, "JSON"));
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("fixture")
  class Fixture {

    @Test
    @DisplayName("fixtureAsMetaSignal 5건 이상 반환 + 모두 tier=2 (H1 amend)")
    void atLeast5Signals_allTier2() {
      List<WikipediaMetaSignal> signals = adapter.fixtureAsMetaSignal();
      assertThat(signals).hasSizeGreaterThanOrEqualTo(5);
      assertThat(signals).allMatch(s -> s.metaResult().tier() == (short) 2);
      assertThat(signals).allMatch(s -> s.metaResult().disclaimerRequired());
      assertThat(signals).allMatch(s -> !s.metaResult().factcheckCacheable());
    }

    @Test
    @DisplayName(
        "fixtureAsMetaSignal — Tier 2 disclaimer + factcheckCacheable=false 강제 (Round 6 H-R5-2 amend, fixture() override 제거 후 대체)")
    void fixtureAsMetaSignal_enforcesTier2Contract() {
      List<WikipediaMetaSignal> fixtures = adapter.fixtureAsMetaSignal();
      assertThat(fixtures).hasSizeGreaterThanOrEqualTo(5);
      assertThat(fixtures).allMatch(s -> s.metaResult().tier() == (short) 2);
      assertThat(fixtures).allMatch(s -> s.metaResult().disclaimerRequired());
      assertThat(fixtures).allMatch(s -> !s.metaResult().factcheckCacheable());
    }
  }

  @Nested
  @DisplayName("metadata")
  class Metadata {

    @Test
    @DisplayName("name=Wikipedia, isPaid=false, provider=wikipedia.org")
    void metadataValues() {
      AdapterMetadata meta = adapter.metadata();
      assertThat(meta.name()).isEqualTo("Wikipedia");
      assertThat(meta.isPaid()).isFalse();
      assertThat(meta.provider()).isEqualTo("wikipedia.org");
    }
  }
}
