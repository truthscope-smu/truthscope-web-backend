package com.truthscope.web.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.truthscope.web.audit.KeyFingerprinter;
import com.truthscope.web.entity.ApiUsageLog;
import com.truthscope.web.repository.ApiUsageLogRepository;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * BE #87+#92 BYOK 실 HTTP 통합 test — BE #74 후속.
 *
 * <p>PLAN rev.3 commit #9 영역 완료 + BE #87 cassette runbook §"후속 phase 실 HTTP 통합 강화 옵션 1" 적용.
 * `wiremock-spring-boot:3.10.0`의 {@code @EnableWireMock} +
 * {@code @ConfigureWireMock(baseUrlProperties)}로 Spring Boot Environment에 WireMock URL을 자동 주입 →
 * production {@link com.truthscope.web.gemini.GeminiClient} + production RestClient bean이 cassette
 * server로 redirect됨.
 *
 * <p>BE #87에서 시도한 3 패턴 (`@DynamicPropertySource` / `@TestPropertySource` /
 * `@TestConfiguration @Import`)이 GeminiClient의 RestClient inject path에 반영되지 않은 케이스를
 * wiremock-spring-boot로 폐쇄. {@code @MockBean GeminiClient} 제거 후 실 HTTP 경계 검증.
 *
 * <p>4 시나리오 (ADR-004 §f "모든 Gemini 호출 기록" 정합):
 *
 * <ul>
 *   <li>S1 SERVER_POOL — userApiKey null → key_source=SERVER_POOL, key_fingerprint=null
 *   <li>S2 BYOK 성공 — 유효 키 200 → key_source=BYOK, key_fingerprint=16 hex
 *   <li>S3 BYOK 실패 fallback — 401 → authFailed → 서버 키 retry → audit 2 row (BYOK_FAILED +
 *       SERVER_POOL_FALLBACK)
 *   <li>S4 CB 개입 — FORCED_OPEN → CallNotPermittedException → BYOK 성공 분류 → audit 1 row (BYOK)
 * </ul>
 *
 * <p>cassette: {@code src/test/resources/wiremock/gemini-claim-extract-success.json} — record-mode
 * 갱신 절차는 {@code .plans/be74-gemini-claim-extractor-2026-05-27/_cassette-runbook.md} 참조.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "truthscope.gemini.api-key=test-server-key",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@ActiveProfiles("production")
@Testcontainers(disabledWithoutDocker = true)
@EnableWireMock(
    @ConfigureWireMock(name = "gemini", baseUrlProperties = "truthscope.gemini.base-url"))
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@DisplayName("BE #87+#92 BYOK 실 HTTP 통합 test (BE #74 후속)")
class GeminiByokIntegrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @InjectWireMock("gemini")
  WireMockServer wireMock;

  @Autowired ClaimAnalysisPort claimAnalysisPort;
  @Autowired ApiUsageLogRepository apiUsageLogRepository;
  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("geminiRestClient")
  org.springframework.web.client.RestClient injectedGeminiRestClient;

  private String cassetteBody;

  @BeforeEach
  void setUp() throws IOException {
    // wiremock-spring-boot 3.10.0 + JdkClientHttpRequestFactory 조합 quirk warmup —
    // GeminiClient가 inject받은 RestClient bean이 첫 callStructured 호출 전에 wireMock URL을 정확히 bind하도록
    // RestClient endpoint 사전 호출 (WireMock /__admin endpoint는 stub 매칭과 무관). 이 warmup 없으면 첫 method 호출
    // 시 RestClient가 application.yml의 default URL을 사용하는 케이스가 함께 실행 시 관찰됨.
    injectedGeminiRestClient.get().uri("/__admin/mappings").retrieve().toBodilessEntity();

    wireMock.resetAll();
    apiUsageLogRepository.deleteAll();
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("gemini");
    cb.transitionToClosedState();
    cb.reset();

    cassetteBody =
        StreamUtils.copyToString(
            new ClassPathResource("wiremock/gemini-claim-extract-success.json").getInputStream(),
            java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("S1 SERVER_POOL — userApiKey null → key_source=SERVER_POOL, key_fingerprint=null")
  void scenario1_serverPool_userApiKeyNull_auditSingleServerPoolRow() {
    wireMock.stubFor(
        post(urlPathMatching("/v1beta/models/gemini-3\\.1-flash-lite:generateContent"))
            .withHeader("x-goog-api-key", equalTo("test-server-key"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(cassetteBody)));

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", null);

    assertThat(drafts).hasSize(1);
    assertThat(wireMock.getAllServeEvents()).hasSize(1);
    assertThat(wireMock.getAllServeEvents().get(0).getWasMatched()).isTrue();

    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getProvider()).isEqualTo("GEMINI");
    assertThat(row.getKeySource()).isEqualTo("SERVER_POOL");
    assertThat(row.getKeyFingerprint()).isNull();
    assertThat(row.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("S2 BYOK 성공 — userApiKey 유효 → key_source=BYOK, key_fingerprint=16 hex")
  void scenario2_byokSuccess_userKeyValid_auditSingleByokRow() {
    String userKey = "user-byok-valid-key-001";
    String expectedFingerprint = KeyFingerprinter.fingerprint(userKey);

    wireMock.stubFor(
        post(urlPathMatching("/v1beta/models/gemini-3\\.1-flash-lite:generateContent"))
            .withHeader("x-goog-api-key", equalTo(userKey))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(cassetteBody)));

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", userKey);

    assertThat(drafts).hasSize(1);
    assertThat(wireMock.getAllServeEvents()).hasSize(1);
    assertThat(wireMock.getAllServeEvents().get(0).getWasMatched()).isTrue();

    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getProvider()).isEqualTo("GEMINI");
    assertThat(row.getKeySource()).isEqualTo("BYOK");
    assertThat(row.getKeyFingerprint()).isEqualTo(expectedFingerprint);
    assertThat(row.getKeyFingerprint()).hasSize(16);
  }

  @Test
  @DisplayName("S3 BYOK 실패 fallback — 401 → authFailed → 서버 키 retry → audit 2 row")
  void scenario3_byokAuthFailed_fallbackToServerPool_auditTwoRows() {
    String badUserKey = "user-byok-bad-key-401";
    String expectedFingerprint = KeyFingerprinter.fingerprint(badUserKey);

    wireMock.stubFor(
        post(urlPathMatching("/v1beta/models/gemini-3\\.1-flash-lite:generateContent"))
            .withHeader("x-goog-api-key", equalTo(badUserKey))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody("{\"error\":{\"code\":401,\"message\":\"API key not valid\"}}")));

    wireMock.stubFor(
        post(urlPathMatching("/v1beta/models/gemini-3\\.1-flash-lite:generateContent"))
            .withHeader("x-goog-api-key", equalTo("test-server-key"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json;charset=UTF-8")
                    .withBody(cassetteBody)));

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", badUserKey);

    assertThat(drafts).hasSize(1);
    assertThat(wireMock.getAllServeEvents()).hasSize(2);

    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(2);

    ApiUsageLog byokFailedRow =
        logs.stream().filter(l -> "BYOK_FAILED".equals(l.getKeySource())).findFirst().orElseThrow();
    assertThat(byokFailedRow.getKeyFingerprint()).isEqualTo(expectedFingerprint);
    assertThat(byokFailedRow.getKeyFingerprint()).hasSize(16);

    ApiUsageLog serverPoolFallbackRow =
        logs.stream()
            .filter(l -> "SERVER_POOL_FALLBACK".equals(l.getKeySource()))
            .findFirst()
            .orElseThrow();
    assertThat(serverPoolFallbackRow.getKeyFingerprint()).isNull();
  }

  @Test
  @DisplayName("S4 CB 개입 — FORCED_OPEN → BYOK 성공 분류 (키 유효성 무관) → audit 1 row (BYOK)")
  void scenario4_circuitBreakerForcedOpen_byokClassifiedAsSuccess_auditByokRow() {
    String userKey = "user-byok-valid-key-cb";
    String expectedFingerprint = KeyFingerprinter.fingerprint(userKey);

    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("gemini");
    cb.transitionToForcedOpenState();
    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", userKey);

    assertThat(drafts).isEmpty();
    assertThat(wireMock.getAllServeEvents()).isEmpty();

    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getKeySource()).isEqualTo("BYOK");
    assertThat(row.getKeyFingerprint()).isEqualTo(expectedFingerprint);
  }
}
