package com.truthscope.web.service.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.claim.validation.ClaimSchemaValidator;
import com.truthscope.web.claim.validation.Tier3ReasonValidator;
import com.truthscope.web.entity.enums.DecisionSource;
import com.truthscope.web.gemini.GeminiClient;
import com.truthscope.web.gemini.GeminiRequest;
import com.truthscope.web.gemini.GeminiResponse;
import com.truthscope.web.prompt.PromptShield;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.service.audit.ApiUsageLogService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ClaimAnalysisService BYOK 분기 단위 테스트 (BE #74).
 *
 * <p>4 시나리오: SERVER_POOL / BYOK 성공 / BYOK_FAILED+SERVER_POOL_FALLBACK / CB 개입 시 BYOK 분류 유지.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClaimAnalysisService BYOK 분기 단위 테스트")
class ClaimAnalysisServiceByokTest {

  @Mock private GeminiClient geminiClient;
  @Mock private PromptShield promptShield;
  @Mock private ClaimSchemaValidator schemaValidator;
  @Mock private Tier3ReasonValidator tier3Validator;
  @Mock private ApiUsageLogService apiUsageLogService;

  private ClaimAnalysisService service;

  private static final String SAMPLE_ARTICLE = "정부는 2025년 GDP 성장률이 3%라고 발표했다.";
  private static final String ASSEMBLED_PROMPT = "<prompt>XML 격리된 본문</prompt>";
  private static final String USER_KEY = "AIzaSyUserKeyExample";

  @BeforeEach
  void setUp() {
    service =
        new ClaimAnalysisService(
            geminiClient, promptShield, schemaValidator, tier3Validator, apiUsageLogService);
  }

  private ClaimDraft buildDraft() {
    return new ClaimDraft(
        UUID.randomUUID(), "claim text", null, false, null, ClaimStatusCandidate.SCORABLE, null);
  }

  @Test
  @DisplayName("Scenario1_userApiKey_null_서버키_호출_SERVER_POOL_audit")
  void scenario1_userApiKeyNull_serverPool() {
    ClaimDraft draft = buildDraft();
    GeminiResponse response = new GeminiResponse(List.of(draft), DecisionSource.GEMINI, false);
    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class), isNull())).thenReturn(response);
    when(schemaValidator.isValid(draft)).thenReturn(true);
    when(tier3Validator.validate(draft)).thenReturn(Optional.empty());

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE, null);

    assertThat(result).hasSize(1);
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), isNull());
    verify(apiUsageLogService, times(1)).record("GEMINI", 0, "SERVER_POOL", null);
  }

  @Test
  @DisplayName("Scenario2_userApiKey_명시_BYOK_성공_BYOK_audit")
  void scenario2_byokSuccess() {
    ClaimDraft draft = buildDraft();
    GeminiResponse response = new GeminiResponse(List.of(draft), DecisionSource.GEMINI, false);
    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class), eq(USER_KEY))).thenReturn(response);
    when(schemaValidator.isValid(draft)).thenReturn(true);
    when(tier3Validator.validate(draft)).thenReturn(Optional.empty());

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE, USER_KEY);

    assertThat(result).hasSize(1);
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(USER_KEY));
    verify(geminiClient, never()).callStructured(any(GeminiRequest.class), isNull());
    verify(apiUsageLogService, times(1)).record(eq("GEMINI"), anyInt(), eq("BYOK"), anyString());
  }

  @Test
  @DisplayName("Scenario3_userApiKey_authFailure_BYOK_FAILED_SERVER_POOL_FALLBACK_audit_2row")
  void scenario3_byokAuthFailed_fallback_audit2row() {
    ClaimDraft draft = buildDraft();
    GeminiResponse authFailed = GeminiResponse.authFailed();
    GeminiResponse fallbackResponse =
        new GeminiResponse(List.of(draft), DecisionSource.GEMINI, false);
    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class), eq(USER_KEY)))
        .thenReturn(authFailed);
    when(geminiClient.callStructured(any(GeminiRequest.class), isNull()))
        .thenReturn(fallbackResponse);
    when(schemaValidator.isValid(draft)).thenReturn(true);
    when(tier3Validator.validate(draft)).thenReturn(Optional.empty());

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE, USER_KEY);

    assertThat(result).hasSize(1);
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(USER_KEY));
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), isNull());
    // audit 2 row — ADR-004 §f "모든 Gemini 호출 기록" 정합
    verify(apiUsageLogService, times(1))
        .record(eq("GEMINI"), anyInt(), eq("BYOK_FAILED"), anyString());
    verify(apiUsageLogService, times(1)).record("GEMINI", 0, "SERVER_POOL_FALLBACK", null);
  }

  @Test
  @DisplayName("Scenario4_userApiKey_명시_CIRCUIT_BREAKER_개입_BYOK_분류_유지_fallback_없음")
  void scenario4_byokWithCircuitBreaker_noFallback() {
    GeminiResponse cbResponse = GeminiResponse.insufficient(DecisionSource.CIRCUIT_BREAKER);
    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class), eq(USER_KEY)))
        .thenReturn(cbResponse);

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE, USER_KEY);

    assertThat(result).isEmpty();
    verify(geminiClient, times(1)).callStructured(any(GeminiRequest.class), eq(USER_KEY));
    // CB는 backend health 신호이고 키 유효성과 무관 → fallback 없음 + BYOK audit 1건만
    verify(geminiClient, never()).callStructured(any(GeminiRequest.class), isNull());
    verify(apiUsageLogService, times(1)).record(eq("GEMINI"), anyInt(), eq("BYOK"), anyString());
  }
}
