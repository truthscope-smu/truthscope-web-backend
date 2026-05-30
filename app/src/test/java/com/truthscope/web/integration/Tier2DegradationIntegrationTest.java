package com.truthscope.web.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Phase 66b T10 신뢰성 통합 테스트 (8-3 축) — 결함 주입.
 *
 * <p>data.go.kr 외부 API 장애 시 Tier 3 안전강하 동작을 검증한다. 시스템이 예외를 던지지 않고 Tier 3 INSUFFICIENT 로 정상 종료함을
 * 입증한다.
 *
 * <p>검증 시나리오:
 *
 * <ul>
 *   <li>D-1 resultCode=3 (NODATA_ERROR): data.go.kr 가 데이터 없음을 반환 -> evidence 0 -> Tier 3.
 *   <li>D-2 HTTP 500: data.go.kr 가 서버 오류를 반환 -> DataGoKrAdapter 내부 catch -> evidence 0 -> Tier 3.
 * </ul>
 *
 * <p>공통 설정: @ActiveProfiles 미사용 (non-production) → FidelityClassifierStubService 활성. 두 시나리오 모두
 * WireMock 으로 data.go.kr 정책뉴스/보도자료 양쪽 API 에 결함 응답 주입.
 *
 * <p>테스트 레벨: VerificationCascadeService.cascade() 직접 호출 (Tier2PolicyEvidenceIntegrationTest 와 동일
 * 레벨). AnalysisService 전체를 구동하지 않아 테스트 의도 명확성 + 단순성 유지.
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
      name = "data-go-kr-policy-deg",
      baseUrlProperties = "truthscope.datasource.policy-news.base-url"),
  @ConfigureWireMock(
      name = "data-go-kr-press-deg",
      baseUrlProperties = "truthscope.datasource.press-release.base-url")
})
@TestPropertySource(
    properties = {
      "truthscope.datasource.data-go-kr-key=test-dummy-key",
      "spring.main.allow-bean-definition-overriding=true"
    })
@DisplayName("Phase 66b T10 신뢰성 통합 테스트 (8-3 — 결함 주입 -> Tier 3 안전강하)")
class Tier2DegradationIntegrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @InjectWireMock("data-go-kr-policy-deg")
  WireMockServer wireMockPolicy;

  @InjectWireMock("data-go-kr-press-deg")
  WireMockServer wireMockPress;

  @MockBean FactcheckCacheRepository factcheckCacheRepository;

  // UrlValidator: HEAD 요청 차단 (결함 시나리오에서는 evidence 0 이므로 실제로 호출되지 않지만 Bean 충돌 방지)
  @MockBean UrlValidator urlValidator;

  @Autowired VerificationCascadeService verificationCascadeService;

  // resultCode=3 NODATA_ERROR XML
  private static final String NODATA_XML =
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

    // Tier 1 miss 강제 (Tier 2 경로 진입 후 결함 주입 확인)
    when(factcheckCacheRepository.searchByText(anyString())).thenReturn(List.of());

    // UrlValidator: evidence 0 경우 validate 호출 없음 — 방어적 stub
    when(urlValidator.validate(anyString())).thenReturn(false);
  }

  /**
   * D-1 data.go.kr resultCode=3 (NODATA_ERROR) -> evidence 0 -> Tier 3 안전강하.
   *
   * <p>정책뉴스 + 보도자료 양쪽 모두 NODATA_ERROR 반환 시, DataGoKrAdapter.parseXml 이 빈 리스트를 반환하고
   * HybridCascadeService.retrieve 가 빈 결과를 반환하여 cascadeOne 이 Tier 3 경로를 밟는다.
   *
   * <p>단언: 예외 미발생 + tier=3 + status INSUFFICIENT.
   */
  @Test
  @DisplayName("D-1 NODATA_ERROR(resultCode=3) -> retrieve 빈 결과 -> Tier 3 INSUFFICIENT, 예외 없음")
  void nodataError_retrieveEmpty_tier3Insufficient_noException() {
    // Given: 양쪽 API 모두 NODATA_ERROR
    wireMockPolicy.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/xml;charset=UTF-8")
                    .withBody(NODATA_XML)));
    wireMockPress.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/xml;charset=UTF-8")
                    .withBody(NODATA_XML)));

    ClaimDraft draft =
        new ClaimDraft(
            UUID.randomUUID(),
            "에너지 정책 NODATA 결함 주입 시나리오",
            null,
            false,
            null,
            ClaimStatusCandidate.SCORABLE,
            null);

    // When: cascade — 예외 없이 정상 종료 확인
    assertThatCode(() -> verificationCascadeService.cascade(List.of(draft)))
        .doesNotThrowAnyException();

    List<ClaimCascadeResult> results = verificationCascadeService.cascade(List.of(draft));

    // Then: Tier 3 INSUFFICIENT — 시스템 미정지 확인
    assertThat(results).hasSize(1);
    ClaimCascadeResult result = results.get(0);

    assertThat(result.signal().tier()).isEqualTo((short) 3);
    // 비판정 상태 (INSUFFICIENT / TIME_SENSITIVE / OUT_OF_SCOPE 중 하나)
    assertThat(result.signal().status()).isNotEqualTo(ClaimScoreStatus.SCORABLE);
    // Tier 3 score = null (domain-logic.md Tier 3 원칙)
    assertThat(result.signal().score()).isNull();
    // evidence 없음 (Tier 3 경로)
    assertThat(result.evidence()).isEmpty();
  }

  /**
   * D-2 data.go.kr HTTP 500 -> DataGoKrAdapter catch -> retrieve 빈 결과 -> Tier 3 안전강하.
   *
   * <p>서버 오류 시 DataGoKrAdapter.fetchWindow 가 RestClientException 을 catch 하여 빈 리스트를 반환한다. 그 결과
   * HybridCascadeService.retrieve 가 빈 결과를 반환하고 cascade 는 Tier 3 로 정상 종료한다.
   *
   * <p>단언: 예외 미발생 + tier=3 + status 비판정.
   */
  @Test
  @DisplayName(
      "D-2 HTTP 500 -> DataGoKrAdapter catch -> retrieve 빈 결과 -> Tier 3 INSUFFICIENT, 예외 없음")
  void http500Error_adapterCatch_tier3Insufficient_noException() {
    // Given: 양쪽 API 모두 HTTP 500 반환
    wireMockPolicy.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("Internal Server Error")));
    wireMockPress.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("Internal Server Error")));

    ClaimDraft draft =
        new ClaimDraft(
            UUID.randomUUID(),
            "에너지 정책 HTTP500 결함 주입 시나리오",
            null,
            false,
            null,
            ClaimStatusCandidate.SCORABLE,
            null);

    // When: cascade — 예외 없이 정상 종료 확인
    assertThatCode(() -> verificationCascadeService.cascade(List.of(draft)))
        .doesNotThrowAnyException();

    List<ClaimCascadeResult> results = verificationCascadeService.cascade(List.of(draft));

    // Then: Tier 3 — 시스템 미정지 + 안전강하 확인
    assertThat(results).hasSize(1);
    ClaimCascadeResult result = results.get(0);

    assertThat(result.signal().tier()).isEqualTo((short) 3);
    assertThat(result.signal().status()).isNotEqualTo(ClaimScoreStatus.SCORABLE);
    assertThat(result.signal().score()).isNull();
    assertThat(result.evidence()).isEmpty();
  }
}
