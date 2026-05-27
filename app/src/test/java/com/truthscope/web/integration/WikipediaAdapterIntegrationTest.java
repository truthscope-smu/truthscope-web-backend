package com.truthscope.web.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.truthscope.web.adapter.datasource.AdapterQuery;
import com.truthscope.web.adapter.datasource.VandalismStatus;
import com.truthscope.web.adapter.datasource.WikipediaAdapter;
import com.truthscope.web.adapter.datasource.WikipediaMetaSignal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * WikipediaAdapter WireMock 통합 테스트 — BE #73.
 *
 * <p>BE #92 wiremock-spring-boot 패턴 정합:
 * {@code @EnableWireMock(@ConfigureWireMock(baseUrlProperties))}로 Spring Boot Environment에
 * Wikipedia ko/en base URL을 WireMock URL로 주입.
 *
 * <p>5 시나리오:
 *
 * <ul>
 *   <li>S1 meta 조회 happy path — STABLE → WikipediaMetaSignal 1건 반환
 *   <li>S2 vandalism 차단 — UNSTABLE (6회 이상 수정) → 빈 리스트 반환
 *   <li>S3 Tier 1 사용 차단 — factcheckCacheable=false 계약 검증
 *   <li>S4 WikipediaMetaSignal FactcheckCache 저장 불가 검증 (H3 옵션 B)
 *   <li>S5 WikipediaSignalConsumer port 외 경로 ArchUnit FAIL 검증 (H3 Round 3 amend)
 * </ul>
 *
 * <p>cassette 파일:
 *
 * <ul>
 *   <li>{@code wiremock/wikipedia-summary-stable.json} — summary API 200 응답
 *   <li>{@code wiremock/wikipedia-revisions-stable.json} — revisions API 최근 2건 (24h 내)
 *   <li>{@code wiremock/wikipedia-revisions-unstable.json} — revisions API 최근 8건 (24h 내)
 * </ul>
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@ActiveProfiles(
    "production") // H5 amend: BE #92 GeminiByokIntegrationTest 패턴 정합 — default URL 누출 quirk 방지
@Testcontainers(disabledWithoutDocker = true)
@EnableWireMock({
  @ConfigureWireMock(name = "wikipedia-ko", baseUrlProperties = "truthscope.wikipedia.ko-base-url"),
  @ConfigureWireMock(name = "wikipedia-en", baseUrlProperties = "truthscope.wikipedia.en-base-url")
})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@DisplayName("WikipediaAdapter WireMock 통합 테스트 (BE #73)")
class WikipediaAdapterIntegrationTest {

  /**
   * H4 codex Round 2 amend: @TestConfiguration으로 Clock.fixed() 주입. cassette revision timestamp 기준
   * 24h 경계를 mock — CI replay 시 Instant.now() 기준 cutoff가 cassette 기간을 벗어나 count=0 → STABLE 오분류 발생하는
   * quirk 차단.
   *
   * <p>[Amend 3] testClock → systemClock 통일: WikipediaRevisionChecker(Clock clock) 주입 시
   * NoUniqueBeanDefinitionException 방지. spring.main.allow-bean-definition-overriding=true
   * (@TestPropertySource 이미 적용) 조건에서 @TestConfiguration이 production ClockConfig @Bean보다 후순위로 등록되므로
   * override 가능.
   */
  @TestConfiguration
  static class ClockTestConfig {
    @Bean
    public Clock systemClock() {
      // cassette revision timestamp 기준 시점: 2026-05-27T11:00:00Z (cassette stable 최신 = 10:00:00Z)
      // 24h cutoff = 2026-05-26T11:00:00Z → cassette revisions (2026-05-27T10:00:00Z 등) 24h 이내로 인식
      return Clock.fixed(Instant.parse("2026-05-27T11:00:00Z"), ZoneOffset.UTC);
    }
  }

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @InjectWireMock("wikipedia-ko")
  WireMockServer wireMockKo;

  @InjectWireMock("wikipedia-en")
  WireMockServer wireMockEn;

  @Autowired WikipediaAdapter wikipediaAdapter;

  private String summaryCassetteStable;
  private String revisionsCassetteStable;
  private String revisionsCassetteUnstable;

  @BeforeEach
  void setUp() throws Exception {
    // H5 amend: BE #92 GeminiByokIntegrationTest.java warmup 패턴 정합.
    // 첫 호출이 default URL(ko.wikipedia.org)로 새는 RestClient bind timing quirk 방지.
    // health() 호출로 RestClient를 WireMock URL에 바인딩 후 stub 등록.
    wireMockKo.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withStatus(200)
                    .withBody("{}")));
    try {
      wikipediaAdapter.health();
    } catch (Exception ignored) {
      /* warmup 목적 — 결과 무시 */
    }
    wireMockKo.resetAll();
    wireMockEn.resetAll();
    summaryCassetteStable =
        StreamUtils.copyToString(
            new ClassPathResource("wiremock/wikipedia-summary-stable.json").getInputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
    revisionsCassetteStable =
        StreamUtils.copyToString(
            new ClassPathResource("wiremock/wikipedia-revisions-stable.json").getInputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
    revisionsCassetteUnstable =
        StreamUtils.copyToString(
            new ClassPathResource("wiremock/wikipedia-revisions-unstable.json").getInputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("S1 meta 조회 happy path — STABLE → WikipediaMetaSignal 1건, tier=2")
  void scenario1_metaFetch_stable_returnsMetaClaim() throws Exception {
    wireMockKo.stubFor(
        get(urlPathMatching("/api/rest_v1/page/summary/.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(summaryCassetteStable)));
    wireMockKo.stubFor(
        get(urlPathMatching("/w/api.php.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(revisionsCassetteStable)));

    // [Amend 2] fetch() 봉인 → fetchMetaSignal() 단일 퍼블릭 API 사용.
    AdapterQuery query = new AdapterQuery("삼성전자", "ko", null, null, 1);
    List<WikipediaMetaSignal> signals = wikipediaAdapter.fetchMetaSignal(query);

    assertThat(signals).hasSize(1);
    assertThat(signals.get(0).metaResult().tier()).isEqualTo((short) 2);
    assertThat(signals.get(0).metaResult().pageUrl()).contains("wikipedia.org");
    assertThat(signals.get(0).metaResult().disclaimerRequired()).isTrue();
  }

  @Test
  @DisplayName("S2 vandalism 차단 — UNSTABLE (6회 이상) → 빈 리스트 반환")
  void scenario2_vandalism_unstable_returnsEmpty() throws Exception {
    wireMockKo.stubFor(
        get(urlPathMatching("/api/rest_v1/page/summary/.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(summaryCassetteStable)));
    wireMockKo.stubFor(
        get(urlPathMatching("/w/api.php.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(revisionsCassetteUnstable)));

    // [Amend 2] unstable cassette(revisions 8건 24h 이내) 주입 → WikipediaRevisionChecker가 UNSTABLE 판정 →
    // 빈 리스트.
    AdapterQuery query = new AdapterQuery("정치뉴스속보", "ko", null, null, 1);
    List<WikipediaMetaSignal> signals = wikipediaAdapter.fetchMetaSignal(query);

    assertThat(signals).isEmpty();
  }

  @Test
  @DisplayName("S3 Tier 1 사용 차단 — factcheckCacheable=false 계약 보장 (WikipediaMetaResult 계약)")
  void scenario3_tier1Guard_factcheckCacheable_alwaysFalse() {
    // WikipediaMetaResult 생성자에서 factcheckCacheable=true 전달 시 IllegalArgumentException 발생 확인
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new com.truthscope.web.adapter.datasource.WikipediaMetaResult(
                    "제목",
                    "설명",
                    "https://ko.wikipedia.org/wiki/제목",
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
      "S4 WikipediaMetaSignal → FactcheckCache 저장 시도 시 factcheckCacheable=false 계약 위반 검증 (H3 옵션 B)")
  void scenario4_wikipediaMetaSignal_notCacheable_contractViolation() {
    // WikipediaMetaResult.of()로 생성된 객체는 항상 factcheckCacheable=false
    com.truthscope.web.adapter.datasource.WikipediaMetaResult metaResult =
        com.truthscope.web.adapter.datasource.WikipediaMetaResult.of(
            "삼성전자",
            "대한민국 기업",
            "https://ko.wikipedia.org/wiki/삼성전자",
            "ko",
            com.truthscope.web.adapter.datasource.VandalismStatus.STABLE);

    // factcheckCacheable=false → FactcheckCache 저장 경로 진입 불가
    assertThat(metaResult.factcheckCacheable()).isFalse();
    // ArchUnit 룰(H3 amend)이 컴파일 타임에 이미 adapter.datasource → FactcheckCacheRepository 의존 차단.
    // 본 테스트는 데이터 흐름 검증의 런타임 보완.
  }

  // [Amend 1] H4-R4 S5 실 fail 검증 — pass-only 안티패턴 수정.
  // ClassFileImporter로 의도적 위반 픽스처(WikipediaSignalViolatorFixture)를 직접 임포트하여
  // wikipediaSignalDependentsMustNotAccessFactcheckCacheRepository 룰을 check() 호출.
  // 룰이 실제로 위반을 감지하여 AssertionError를 던지는지 assertThatThrownBy로 검증.
  // [Fix] 픽스처 클래스명에 "WikipediaSignal" 포함 필수 — ArchUnit 룰이
  // haveSimpleNameContaining("WikipediaSignal")
  //   필터를 사용하므로, 픽스처 클래스명이 이를 만족해야 실제 위반 감지 가능. WikipediaViolatorFixture →
  //   WikipediaSignalViolatorFixture 리네임으로 룰 적용 범위에 포함.
  @Test
  @DisplayName(
      "S5 WikipediaSignalConsumer port 외 경로에서 FactcheckCacheRepository 접근 시 ArchUnit FAIL 검증 (H3 codex Round 3 amend + Amend 1 실 fail 검증)")
  void scenario5_nonConsumerPort_factcheckCacheRepository_archunitFail() {
    // archtestNegative: 의도적 위반 픽스처 클래스를 ClassFileImporter로 직접 임포트
    com.tngtech.archunit.core.importer.ClassFileImporter importer =
        new com.tngtech.archunit.core.importer.ClassFileImporter();
    com.tngtech.archunit.core.domain.JavaClasses violationClasses =
        importer.importClasses(WikipediaSignalViolatorFixture.class);

    // ArchUnit 룰이 실제 위반을 감지하여 AssertionError를 던지는지 검증
    // 룰이 동작하지 않으면 예외가 발생하지 않아 이 테스트 자체가 fail → pass-only 안티패턴 차단
    // Round 6 amend: ArchitectureTest의 룰 필드를 public static으로 노출하여
    //   integration 패키지 테스트에서 FQN으로 직접 호출 가능.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                com.truthscope.web.architecture.ArchitectureTest
                    .wikipediaSignalDependentsMustNotAccessFactcheckCacheRepository
                    .check(violationClasses))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("FactcheckCacheRepository");
  }

  /**
   * 의도적 위반 픽스처 — ArchUnit S5 실 fail 검증용.
   *
   * <p>WikipediaMetaSignal 의존 + FactcheckCacheRepository 직접 접근 의도적 위반. WikipediaSignalConsumer 미구현
   * + FactcheckCacheRepository 접근 → 4번째 ArchUnit 룰 위반 확인용. 실 서비스 코드에 포함되지 않는다 (테스트 파일 내 inner
   * static class로만 존재).
   *
   * <p>[Fix] 클래스명에 "WikipediaSignal" 포함 — ArchUnit 룰 haveSimpleNameContaining("WikipediaSignal")
   * 필터가 이 픽스처를 대상으로 삼도록 보장. 이전 이름 WikipediaViolatorFixture는 필터 미통과로 S5 PASS-ONLY 안티패턴 발생.
   */
  static class WikipediaSignalViolatorFixture {
    com.truthscope.web.adapter.datasource.WikipediaMetaSignal signal;
    com.truthscope.web.repository.FactcheckCacheRepository repo;

    void violate() {
      // WikipediaMetaSignal 의존 + FactcheckCacheRepository 직접 접근 — 의도적 위반
      // ArchUnit 4번째 룰(WikipediaMetaSignal 의존 클래스의 FactcheckCacheRepository 접근 금지)을 위반
      if (signal != null && repo != null) {
        repo.save(com.truthscope.web.entity.FactcheckCache.builder().build());
      }
    }
  }
}
