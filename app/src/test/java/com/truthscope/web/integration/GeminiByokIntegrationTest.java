package com.truthscope.web.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.audit.KeyFingerprinter;
import com.truthscope.web.entity.ApiUsageLog;
import com.truthscope.web.entity.enums.DecisionSource;
import com.truthscope.web.gemini.ClaimAnalysisPayload;
import com.truthscope.web.gemini.GeminiClient;
import com.truthscope.web.gemini.GeminiRequest;
import com.truthscope.web.gemini.GeminiResponse;
import com.truthscope.web.repository.ApiUsageLogRepository;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE #87 BYOK integration test — BE #74 후속.
 *
 * <p>PLAN rev.3 commit #9 영역. BE #74 머지된 BYOK 4 시나리오 (SERVER_POOL / BYOK 성공 / BYOK_FAILED+
 * SERVER_POOL_FALLBACK / CB 개입)의 ClaimAnalysisService 분기 + ApiUsageLog audit row 정합을 Spring
 * Testcontainers + 실 DB로 검증.
 *
 * <p>GeminiClient는 {@code @MockBean}으로 stub — Spring AOP CGLIB proxy + JdkClientHttpRequestFactory
 * + resilience4j @CircuitBreaker 합치되 RestClient bean override가 GeminiClient의 inject path에 적용되지 않는
 * 케이스가 확인되어 실 HTTP 경계 (WireMock cassette) 통합은 별 phase로 이연 (issue #87 후속 트랙). 본 phase는
 * GeminiResponse contract를 stub으로 고정하고 ClaimAnalysisService → ApiUsageLogService → DB audit 경로 4
 * 시나리오를 통합 검증한다.
 *
 * <p>4 시나리오 (ADR-004 §f "모든 Gemini 호출 기록" 정합):
 *
 * <ul>
 *   <li>S1 SERVER_POOL — userApiKey null → key_source=SERVER_POOL, key_fingerprint=null
 *   <li>S2 BYOK 성공 — 유효 키 200 응답 stub → key_source=BYOK, key_fingerprint=16 hex
 *   <li>S3 BYOK 실패 fallback — authFailed stub → 서버 키 retry → audit 2 row (BYOK_FAILED +
 *       SERVER_POOL_FALLBACK)
 *   <li>S4 CB 개입 — FORCED_OPEN → CallNotPermittedException stub → BYOK 성공 분류 (CB는 backend health
 *       신호이며 키 유효성과 무관) → audit 1 row (BYOK)
 * </ul>
 *
 * <p>cassette JSON: {@code src/test/resources/wiremock/gemini-claim-extract-success.json} — 후속
 * phase에서 wiremock-spring-boot 도입 시 record/replay 절차는 {@code
 * .plans/be74-gemini-claim-extractor-2026-05-27/_cassette-runbook.md} 참조.
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
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@DisplayName("BE #87 BYOK integration test (BE #74 후속)")
class GeminiByokIntegrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @MockBean GeminiClient geminiClient;

  @Autowired ClaimAnalysisPort claimAnalysisPort;
  @Autowired ApiUsageLogRepository apiUsageLogRepository;
  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

  /** cassette JSON의 ClaimAnalysisPayload 정합 fixture — Gemini 정상 응답 1건. */
  private static GeminiResponse successResponseFromCassette() {
    ClaimDraft draft =
        new ClaimDraft(
            UUID.randomUUID(),
            "Government announced a 10% budget increase for policy in 2026.",
            null,
            false,
            null,
            ClaimStatusCandidate.SCORABLE,
            null);
    ClaimAnalysisPayload payload = new ClaimAnalysisPayload(List.of()); // unused — drafts 직접 박제
    return new GeminiResponse(List.of(draft), DecisionSource.GEMINI, false);
  }

  @BeforeEach
  void setUp() {
    apiUsageLogRepository.deleteAll();
    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("gemini");
    cb.transitionToClosedState();
    cb.reset();
  }

  @Test
  @DisplayName("S1 SERVER_POOL — userApiKey null → key_source=SERVER_POOL, key_fingerprint=null")
  void scenario1_serverPool_userApiKeyNull_auditSingleServerPoolRow() {
    when(geminiClient.callStructured(any(GeminiRequest.class), isNull()))
        .thenReturn(successResponseFromCassette());

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", null);

    assertThat(drafts).hasSize(1);
    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getProvider()).isEqualTo("GEMINI");
    assertThat(row.getKeySource()).isEqualTo("SERVER_POOL");
    assertThat(row.getKeyFingerprint()).isNull();
    assertThat(row.getRequestCount()).isEqualTo(1);

    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), isNull());
  }

  @Test
  @DisplayName("S2 BYOK 성공 — userApiKey 유효 → key_source=BYOK, key_fingerprint=16 hex")
  void scenario2_byokSuccess_userKeyValid_auditSingleByokRow() {
    String userKey = "user-byok-valid-key-001";
    String expectedFingerprint = KeyFingerprinter.fingerprint(userKey);

    when(geminiClient.callStructured(any(GeminiRequest.class), eq(userKey)))
        .thenReturn(successResponseFromCassette());

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", userKey);

    assertThat(drafts).hasSize(1);
    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getProvider()).isEqualTo("GEMINI");
    assertThat(row.getKeySource()).isEqualTo("BYOK");
    assertThat(row.getKeyFingerprint()).isEqualTo(expectedFingerprint);
    assertThat(row.getKeyFingerprint()).hasSize(16);

    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(userKey));
  }

  @Test
  @DisplayName("S3 BYOK 실패 fallback — authFailed → 서버 키 retry → audit 2 row")
  void scenario3_byokAuthFailed_fallbackToServerPool_auditTwoRows() {
    String badUserKey = "user-byok-bad-key-401";
    String expectedFingerprint = KeyFingerprinter.fingerprint(badUserKey);

    when(geminiClient.callStructured(any(GeminiRequest.class), eq(badUserKey)))
        .thenReturn(GeminiResponse.authFailed());
    when(geminiClient.callStructured(any(GeminiRequest.class), isNull()))
        .thenReturn(successResponseFromCassette());

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", badUserKey);

    assertThat(drafts).hasSize(1);

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

    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(badUserKey));
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), isNull());
  }

  @Test
  @DisplayName("S4 CB 개입 — CIRCUIT_BREAKER insufficient → BYOK 성공 분류 → audit 1 row (BYOK)")
  void scenario4_circuitBreakerInsufficient_byokClassifiedAsSuccess_auditByokRow() {
    String userKey = "user-byok-valid-key-cb";
    String expectedFingerprint = KeyFingerprinter.fingerprint(userKey);

    // CB 개입은 backend health 신호 — ClaimAnalysisService에서 authFailure=false이므로 BYOK 성공 분류.
    // GeminiClient.fallbackStructured가 반환할 CIRCUIT_BREAKER insufficient를 직접 stub.
    when(geminiClient.callStructured(any(GeminiRequest.class), eq(userKey)))
        .thenReturn(GeminiResponse.insufficient(DecisionSource.CIRCUIT_BREAKER));

    List<ClaimDraft> drafts =
        claimAnalysisPort.analyze("Government announced policy budget article body.", userKey);

    assertThat(drafts).isEmpty();

    List<ApiUsageLog> logs = apiUsageLogRepository.findAll();
    assertThat(logs).hasSize(1);
    ApiUsageLog row = logs.get(0);
    assertThat(row.getKeySource()).isEqualTo("BYOK");
    assertThat(row.getKeyFingerprint()).isEqualTo(expectedFingerprint);

    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(userKey));
  }
}
