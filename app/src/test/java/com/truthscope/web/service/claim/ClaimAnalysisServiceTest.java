package com.truthscope.web.service.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.claim.validation.ClaimSchemaValidator;
import com.truthscope.web.claim.validation.HeuristicValidator;
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
 * ClaimAnalysisService 단위 테스트.
 *
 * <p>GeminiClient / PromptShield / ClaimSchemaValidator / Tier3ReasonValidator / ApiUsageLogService
 * 를 Mockito mock 으로 격리하여 PLAN §11-1 시나리오를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClaimAnalysisService 단위 테스트")
class ClaimAnalysisServiceTest {

  @Mock private GeminiClient geminiClient;
  @Mock private PromptShield promptShield;
  @Mock private ClaimSchemaValidator schemaValidator;
  @Mock private Tier3ReasonValidator tier3Validator;
  @Mock private ApiUsageLogService apiUsageLogService;

  private ClaimAnalysisService service;

  private static final String SAMPLE_ARTICLE = "정부는 2025년 GDP 성장률이 3%라고 발표했다.";
  private static final String ASSEMBLED_PROMPT = "<prompt>XML 격리된 본문</prompt>";

  @BeforeEach
  void setUp() {
    service =
        new ClaimAnalysisService(
            geminiClient, promptShield, schemaValidator, tier3Validator, apiUsageLogService);
  }

  private ClaimDraft buildDraft(String text, ClaimStatusCandidate status) {
    return new ClaimDraft(UUID.randomUUID(), text, null, false, null, status, null);
  }

  @Test
  @DisplayName("null_articleBody_입력시_빈_리스트_반환_Gemini_호출_0회")
  void nullArticleBody_빈리스트_반환() {
    List<ClaimDraft> result = service.analyze(null);

    assertThat(result).isEmpty();
    verify(geminiClient, never()).callStructured(any());
  }

  @Test
  @DisplayName("blank_articleBody_입력시_빈_리스트_반환_Gemini_호출_0회")
  void blankArticleBody_빈리스트_반환() {
    List<ClaimDraft> result = service.analyze("   ");

    assertThat(result).isEmpty();
    verify(geminiClient, never()).callStructured(any());
  }

  @Test
  @DisplayName("Gemini_정상_호출_schema_통과_tier3_empty시_SCORABLE_ClaimDraft_반환")
  void gemini_정상_schema_통과_tier3_empty_SCORABLE_반환() {
    ClaimDraft geminDraft = buildDraft("정부 발표 claim", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(geminDraft), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(geminDraft)).thenReturn(true);
    when(tier3Validator.validate(geminDraft)).thenReturn(Optional.empty());

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).claimStatusCandidate()).isEqualTo(ClaimStatusCandidate.SCORABLE);
    verify(apiUsageLogService, times(1)).record(anyString(), anyInt());
  }

  @Test
  @DisplayName("Gemini_정상_호출_schema_실패시_INSUFFICIENT_CANDIDATE_강제")
  void schemaValidator_실패_INSUFFICIENT_CANDIDATE_강제() {
    ClaimDraft geminDraft = buildDraft("", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(geminDraft), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(geminDraft)).thenReturn(false);

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.INSUFFICIENT_CANDIDATE);
    // schema 실패 시 tier3Validator 는 호출되지 않아야 함
    verify(tier3Validator, never()).validate(any());
  }

  @Test
  @DisplayName("Gemini_정상_호출_tier3_OUT_OF_SCOPE시_OUT_OF_SCOPE_CANDIDATE_강제")
  void tier3Validator_OUT_OF_SCOPE_candidate_강제() {
    ClaimDraft geminDraft = buildDraft("정치적 의견", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(geminDraft), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(geminDraft)).thenReturn(true);
    when(tier3Validator.validate(geminDraft))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE));

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.OUT_OF_SCOPE_CANDIDATE);
  }

  @Test
  @DisplayName("Gemini_정상_호출_tier3_TIME_SENSITIVE시_TIME_SENSITIVE_CANDIDATE_강제")
  void tier3Validator_TIME_SENSITIVE_candidate_강제() {
    ClaimDraft geminDraft = buildDraft("현재 상황 주장", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(geminDraft), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(geminDraft)).thenReturn(true);
    when(tier3Validator.validate(geminDraft))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE));

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.TIME_SENSITIVE_CANDIDATE);
  }

  @Test
  @DisplayName("Gemini_정상_호출_tier3_INSUFFICIENT시_INSUFFICIENT_CANDIDATE_강제")
  void tier3Validator_INSUFFICIENT_candidate_강제() {
    ClaimDraft geminDraft = buildDraft("근거 없는 주장", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(geminDraft), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(geminDraft)).thenReturn(true);
    when(tier3Validator.validate(geminDraft))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.INSUFFICIENT));

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.INSUFFICIENT_CANDIDATE);
  }

  @Test
  @DisplayName("ApiUsageLogService_record_가_항상_호출된다")
  void apiUsageLogService_record_항상_호출() {
    GeminiResponse emptyResponse = new GeminiResponse(List.of(), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(emptyResponse);

    service.analyze(SAMPLE_ARTICLE);

    verify(apiUsageLogService, times(1)).record("GEMINI", 0);
  }

  @Test
  @DisplayName("Gemini_응답_claim_여러건_각각_validator_독립_적용")
  void 여러_claim_각각_validator_독립_적용() {
    ClaimDraft draft1 = buildDraft("정상 claim", ClaimStatusCandidate.SCORABLE);
    ClaimDraft draft2 = buildDraft("빈 텍스트", ClaimStatusCandidate.SCORABLE);
    GeminiResponse response = new GeminiResponse(List.of(draft1, draft2), DecisionSource.GEMINI);

    when(promptShield.assemble(SAMPLE_ARTICLE)).thenReturn(ASSEMBLED_PROMPT);
    when(geminiClient.callStructured(any(GeminiRequest.class))).thenReturn(response);
    when(schemaValidator.isValid(draft1)).thenReturn(true);
    when(schemaValidator.isValid(draft2)).thenReturn(false);
    when(tier3Validator.validate(draft1)).thenReturn(Optional.empty());

    List<ClaimDraft> result = service.analyze(SAMPLE_ARTICLE);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).claimStatusCandidate()).isEqualTo(ClaimStatusCandidate.SCORABLE);
    assertThat(result.get(1).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.INSUFFICIENT_CANDIDATE);
  }
}
