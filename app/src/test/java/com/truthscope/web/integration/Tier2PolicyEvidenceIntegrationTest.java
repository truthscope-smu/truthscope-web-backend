package com.truthscope.web.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.service.verification.VerificationCascadeService;
import com.truthscope.web.url.UrlValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * Phase 66b T10 기능 적합성 통합 테스트 (8-2 축).
 *
 * <p>WireMock data.go.kr + FidelityClassifierStub (non-production profile 활성) 조합으로
 * HybridCascadeService.retrieve -> VerificationCascadeService.cascade 경로를 검증한다.
 *
 * <p>핵심 검증 대상:
 *
 * <ul>
 *   <li>claimText 가 WireMock XML 의 NewsItem Title 에 포함된 키워드를 보유할 때, stub fidelity 가 SUPPORTED +
 *       matchedFields 를 반환하고 threshold=1 게이트를 통과한다.
 *   <li>cascade 결과 tier=2 + SCORABLE + evidence 1건 이상 반환.
 * </ul>
 *
 * <p>프로파일: @ActiveProfiles 미사용 → 기본 non-production.
 * FidelityClassifierStubService(@Profile("!production")) 가 FidelityClassifierPort 구현체로 활성화된다.
 *
 * <p>WireMock 주입: @EnableWireMock + @ConfigureWireMock(baseUrlProperties) 로 Spring Environment 에
 * policy-news / press-release base-url 을 WireMock URL 로 주입. DataGoKrAdapter 의 @Value 가 이를 읽어
 * restClientBuilder.baseUrl(endpoint) 에 WireMock URL 을 사용하게 된다.
 *
 * <p>UrlValidator @MockBean: production 진입 불가 (non-production) + HEAD 요청 차단 목적. WireMock URL 에 HEAD
 * 요청을 보내지 않고도 validSnapshots 게이트를 통과하도록 항상 true 반환.
 *
 * <p>FactcheckCacheRepository @MockBean: Testcontainers PostgreSQL 에 search_vector tsvector 컬럼 미존재
 * (V8 마이그레이션 이전) → native SQL 실패 우회 + Tier 1 miss 제어.
 *
 * <p>테스트 레벨: VerificationCascadeService.cascade() 직접 호출 (full-pipeline 대신 cascade 레벨).
 * AnalysisService 전체를 구동하면 ContentExtractService / ClaimAnalysisPort 추가 stub 이 필요하고
 * ArticleRepository / ClaimRepository 등 FK cascade cleanup 복잡도가 늘어 테스트 의도가 희석된다. 66b T10
 * 목적(retrieve->cascade->Tier 2 도달)을 직접 검증하는 cascade 레벨로 범위를 제한한다.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "truthscope.gemini.api-key=test-key",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@Testcontainers(disabledWithoutDocker = true)
@EnableWireMock({
  @ConfigureWireMock(
      name = "data-go-kr-policy",
      baseUrlProperties = "truthscope.datasource.policy-news.base-url"),
  @ConfigureWireMock(
      name = "data-go-kr-press",
      baseUrlProperties = "truthscope.datasource.press-release.base-url")
})
@TestPropertySource(
    properties = {
      "truthscope.datasource.data-go-kr-key=test-dummy-key",
      "spring.main.allow-bean-definition-overriding=true"
    })
@DisplayName("Phase 66b T10 기능 적합성 통합 테스트 (8-2 — data.go.kr WireMock + stub fidelity -> Tier 2)")
class Tier2PolicyEvidenceIntegrationTest {

  // Singleton Testcontainers — GeminiByokIntegrationTest / WikipediaAdapterIntegrationTest 패턴 정합
  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @InjectWireMock("data-go-kr-policy")
  WireMockServer wireMockPolicy;

  @InjectWireMock("data-go-kr-press")
  WireMockServer wireMockPress;

  // Tier 1 miss 제어 + search_vector 컬럼 부재 우회
  @MockBean FactcheckCacheRepository factcheckCacheRepository;

  // UrlValidator HEAD 실요청 차단 (WireMock URL 에 HEAD 보내면 404 → validSnapshots 0 → Tier 3 낙하)
  @MockBean UrlValidator urlValidator;

  @Autowired VerificationCascadeService verificationCascadeService;

  // data.go.kr XML 응답 — 정책뉴스 1건. Title 에 "에너지" "정책" 포함 (claim 키워드와 일치).
  // 회귀 가드(production 버그 검출): 실제 data.go.kr 응답이 보내는 미선언 필드(NewsItemId/ContentsStatus/
  // ModifyId/ModifyDate/ApproverName/GroupingCode/SubTitle2/ContentsType/ThumbnailUrl/OriginalimgUrl, §5)를
  // 의도적으로 포함. DataGoKrAdapter의 XmlMapper가 FAIL_ON_UNKNOWN_PROPERTIES=false 여야 파싱 성공한다.
  // 미설정 시 unrecognized field 예외 → 빈 결과 → 항상 Tier 3 (실서비스 무력화 버그).
  private static final String POLICY_NEWS_XML_SINGLE_HIT =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<response>\n"
          + "  <header>\n"
          + "    <resultCode>0</resultCode>\n"
          + "    <resultMsg>NORMAL_SERVICE</resultMsg>\n"
          + "  </header>\n"
          + "  <body>\n"
          + "    <NewsItem>\n"
          + "      <NewsItemId>148964101</NewsItemId>\n"
          + "      <ContentsStatus>I</ContentsStatus>\n"
          + "      <ModifyId>7</ModifyId>\n"
          + "      <ModifyDate>05/08/2026 16:52:10</ModifyDate>\n"
          + "      <ApproveDate>05/08/2026 16:51:00</ApproveDate>\n"
          + "      <ApproverName>선경철</ApproverName>\n"
          + "      <GroupingCode>policy</GroupingCode>\n"
          + "      <Title><![CDATA[2026년 에너지 전환 정책 현황]]></Title>\n"
          + "      <SubTitle2><![CDATA[부제]]></SubTitle2>\n"
          + "      <ContentsType>H</ContentsType>\n"
          + "      <DataContents><![CDATA[정부는 2026년 재생에너지 비중을 30%까지 높이는 에너지 정책 로드맵을 발표했다.]]></DataContents>\n"
          + "      <MinisterCode>산업통상자원부</MinisterCode>\n"
          + "      <OriginalUrl>https://www.korea.kr/news/policyNewsView.do?newsId=148964101</OriginalUrl>\n"
          + "      <ThumbnailUrl>https://www.korea.kr/thumb.png</ThumbnailUrl>\n"
          + "      <OriginalimgUrl>https://www.korea.kr/img.png</OriginalimgUrl>\n"
          + "    </NewsItem>\n"
          + "  </body>\n"
          + "</response>";

  // 보도자료 — NODATA (resultCode=3). 정책뉴스 1건만으로 threshold=1 충족 확인.
  private static final String PRESS_RELEASE_XML_NODATA =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<response>\n"
          + "  <header>\n"
          + "    <resultCode>3</resultCode>\n"
          + "    <resultMsg>NODATA_ERROR</resultMsg>\n"
          + "  </header>\n"
          + "  <body/>\n"
          + "</response>";

  @BeforeEach
  void setUp() {
    wireMockPolicy.resetAll();
    wireMockPress.resetAll();

    // Tier 1 miss — Tier 2 경로 강제
    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());

    // UrlValidator: 모든 URL 유효 반환 → validSnapshots 게이트 통과
    when(urlValidator.validate(anyString())).thenReturn(true);

    // 정책뉴스 API — 정상 XML 반환 (policyNewsList path 매칭)
    wireMockPolicy.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/xml;charset=UTF-8")
                    .withBody(POLICY_NEWS_XML_SINGLE_HIT)));

    // 보도자료 API — NODATA 반환 (pressReleaseList path 매칭)
    wireMockPress.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/xml;charset=UTF-8")
                    .withBody(PRESS_RELEASE_XML_NODATA)));
  }

  /**
   * F-1 Happy path: WireMock data.go.kr 정책뉴스 1건 -> stub fidelity SUPPORTED -> Tier 2 SCORABLE.
   *
   * <p>claimText 에 "에너지 정책" 키워드를 포함시켜 WireMock XML NewsItem Title("2026년 에너지 전환 정책 현황") 과 lexical
   * match 를 유도한다. FidelityClassifierStubService 가 SUPPORTED + matchedFields 1건을 반환하여 validSnapshots
   * size >= threshold(1) 게이트를 통과 → Tier 2 SCORABLE 도달.
   */
  @Test
  @DisplayName(
      "F-1 WireMock 정책뉴스 1건 + stub fidelity SUPPORTED -> cascade Tier 2 SCORABLE + evidence 1건")
  void happyPath_wireMockPolicyNews_stubFidelitySupported_tier2Scorable() {
    // Given: claim 텍스트에 WireMock XML Title 키워드("에너지", "정책") 포함
    String claimText = "정부가 2026년 에너지 전환 정책을 발표했다";
    ClaimDraft draft =
        new ClaimDraft(
            UUID.randomUUID(), claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);

    // When: cascade 실행 — retrieve -> stub fidelity -> Tier 2 게이트
    List<ClaimCascadeResult> results = verificationCascadeService.cascade(List.of(draft));

    // Then: 결과 1건
    assertThat(results).hasSize(1);
    ClaimCascadeResult result = results.get(0);

    // Tier 2 SCORABLE 도달 검증
    assertThat(result.signal().tier()).isEqualTo((short) 2);
    assertThat(result.signal().status()).isEqualTo(ClaimScoreStatus.SCORABLE);
    assertThat(result.signal().score()).isNotNull().isBetween(0, 100);

    // evidence 1건 이상 (WireMock XML 1건 -> stub fidelity SUPPORTED)
    assertThat(result.evidence()).hasSizeGreaterThanOrEqualTo(1);

    // evidence stance 검증 (SUPPORTED — stub fidelity 결정적 반환값)
    assertThat(result.evidence().get(0).stance()).isEqualTo("SUPPORTED");

    // matchedFields 비어 있지 않음 (stub fidelity 결정 규칙: 관련 후보 = 비어있지 않은 matchedFields)
    assertThat(result.evidence().get(0).matchedFields()).isNotEmpty();

    // WireMock 호출 확인 (정책뉴스 API 최소 1회 호출)
    assertThat(wireMockPolicy.getAllServeEvents()).isNotEmpty();
  }

  /**
   * F-2 단일 evidence(threshold=1) 로 Tier 2 SCORABLE 도달 확인 (회귀 시뮬 C-4 sanity).
   *
   * <p>PLAN 8-4 회귀 시뮬 C-4: threshold=1 + 관련 snapshot(SUPPORTED + matchedFields 보유) 1건 -> Tier 2
   * SCORABLE. 이 테스트가 PASS 이면 threshold=1 설정과 단일-소스 경로가 모두 작동함을 증명한다.
   */
  @Test
  @DisplayName("F-2 단일 evidence(threshold=1) Tier 2 SCORABLE 도달 — 회귀 시뮬 C-4 sanity")
  void singleEvidence_thresholdOne_tier2Scorable_regressionSimC4() {
    // Given: 보도자료도 NODATA, 정책뉴스만 1건 매칭 (setUp 에서 이미 설정됨)
    String claimText = "에너지 정책 로드맵 발표";
    ClaimDraft draft =
        new ClaimDraft(
            UUID.randomUUID(), claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);

    // When
    List<ClaimCascadeResult> results = verificationCascadeService.cascade(List.of(draft));

    // Then: Tier 2 + evidence >= 1 (single source threshold=1 충족)
    assertThat(results).hasSize(1);
    assertThat(results.get(0).signal().tier()).isEqualTo((short) 2);
    assertThat(results.get(0).signal().status()).isEqualTo(ClaimScoreStatus.SCORABLE);
    assertThat(results.get(0).evidence()).hasSizeGreaterThanOrEqualTo(1);
  }
}
