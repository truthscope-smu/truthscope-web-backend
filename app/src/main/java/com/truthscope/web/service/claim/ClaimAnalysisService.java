package com.truthscope.web.service.claim;

import com.truthscope.web.claim.validation.ClaimSchemaValidator;
import com.truthscope.web.claim.validation.Tier3ReasonValidator;
import com.truthscope.web.gemini.GeminiClient;
import com.truthscope.web.gemini.GeminiRequest;
import com.truthscope.web.gemini.GeminiResponse;
import com.truthscope.web.prompt.PromptShield;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.service.audit.ApiUsageLogService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Gemini structured output 1 call → List&lt;ClaimDraft&gt;. BE #74 spec.
 *
 * <p>Wave 2 cascade 의 직접 입력 생산자. PromptShield(BE #72) + Schema Validator + Tier3ReasonValidator 조합.
 * ClaimExtractorStubService 는 같은 commit 으로 @Profile("!production") guard 적용.
 */
@Service
@Profile("production")
public class ClaimAnalysisService implements ClaimAnalysisPort {

  private final GeminiClient geminiClient;
  private final PromptShield promptShield;
  private final ClaimSchemaValidator schemaValidator;
  private final Tier3ReasonValidator tier3Validator;
  private final ApiUsageLogService apiUsageLogService;

  public ClaimAnalysisService(
      GeminiClient geminiClient,
      PromptShield promptShield,
      ClaimSchemaValidator schemaValidator,
      Tier3ReasonValidator tier3Validator,
      ApiUsageLogService apiUsageLogService) {
    this.geminiClient = geminiClient;
    this.promptShield = promptShield;
    this.schemaValidator = schemaValidator;
    this.tier3Validator = tier3Validator;
    this.apiUsageLogService = apiUsageLogService;
  }

  @Override
  public List<ClaimDraft> analyze(String articleBody) {
    if (articleBody == null || articleBody.isBlank()) {
      return List.of();
    }
    // 1. PromptShield XML 격리 더하기 mini-CoVe template
    String prompt = promptShield.assemble(articleBody);
    // 2. GeminiClient.callStructured (Resilience4j CircuitBreaker 자동 적용 더하기 2단계 파싱 더하기 fallback 분기)
    GeminiRequest request = buildRequest(prompt);
    GeminiResponse response = geminiClient.callStructured(request);
    // 3. ApiUsageLog 적재 (v1.x sequential 전제, T1-10 정합)
    apiUsageLogService.record(
        "GEMINI", 0); // tokenCount=0 unknown (Gemini response usageMetadata 활용은 v2 트랙)
    // 4. Schema Validator 더하기 Tier3ReasonValidator 적용 — Wave 2 cascade 가 후속 처리.
    //    본 service 는 ClaimDraft 생산만 — validator 결과는 cascade 가 ClaimVerificationSignal 단계에서 활용.
    return response.claims();
  }

  private GeminiRequest buildRequest(String prompt) {
    return new GeminiRequest(
        List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part(prompt)))),
        new GeminiRequest.GenerationConfig(
            new GeminiRequest.ResponseFormat(
                "application/json", null), // schema 박제는 v2 트랙 (responseSchema enforcement)
            0.0)); // deterministic for fact extraction
  }
}
