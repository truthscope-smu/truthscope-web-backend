package com.truthscope.web.service.claim;

import com.truthscope.web.audit.KeyFingerprinter;
import com.truthscope.web.claim.validation.ClaimSchemaValidator;
import com.truthscope.web.claim.validation.HeuristicValidator;
import com.truthscope.web.claim.validation.Tier3ReasonValidator;
import com.truthscope.web.gemini.GeminiClient;
import com.truthscope.web.gemini.GeminiRequest;
import com.truthscope.web.gemini.GeminiResponse;
import com.truthscope.web.prompt.PromptShield;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.service.audit.ApiUsageLogService;
import jakarta.annotation.Nullable;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Gemini structured output 1 call → List&lt;ClaimDraft&gt;. BE #74 spec.
 *
 * <p>Wave 2 cascade 의 직접 입력 생산자. PromptShield(BE #72) + Schema Validator + Tier3ReasonValidator 조합.
 * ClaimExtractorStubService 는 같은 commit 으로 @Profile("!production") guard 적용.
 *
 * <p>PLAN §4-1 step 3/4 흐름:
 *
 * <ol>
 *   <li>PromptShield XML 격리
 *   <li>GeminiClient.callStructured
 *   <li>Schema Validator (Bean Validation 더하기 business rules) — schema 실패 시 INSUFFICIENT_CANDIDATE
 *       강제
 *   <li>Tier3ReasonValidator (Validator &gt; Gemini priority) — OUT_OF_SCOPE / TIME_SENSITIVE /
 *       INSUFFICIENT 분류
 * </ol>
 */
@Service
@Profile("production")
@RequiredArgsConstructor
public class ClaimAnalysisService implements ClaimAnalysisPort {

  private final GeminiClient geminiClient;
  private final PromptShield promptShield;
  private final ClaimSchemaValidator schemaValidator;
  private final Tier3ReasonValidator tier3Validator;
  private final ApiUsageLogService apiUsageLogService;

  @Override
  public List<ClaimDraft> analyze(String articleBody, @Nullable String userApiKey) {
    if (articleBody == null || articleBody.isBlank()) {
      return List.of();
    }
    String prompt = promptShield.assemble(articleBody);
    GeminiRequest request = buildRequest(prompt);

    GeminiResponse response;
    if (userApiKey != null && !userApiKey.isBlank()) {
      response = geminiClient.callStructured(request, userApiKey);
      // BE #74: BYOK 인증 실패 시 서버 기본 키로 1회 재시도. ADR-004 §f "모든 Gemini 호출 기록" 정합 — audit 2 row.
      // CB 개입(CIRCUIT_BREAKER)은 BYOK 실패로 분류하지 않음 — CB는 backend health 신호, 키 유효성과 무관.
      // 정상 SAFETY/empty/parse failure도 BYOK 실패가 아니라 정상 응답의 한 case.
      if (response.authFailure()) {
        String fingerprint = KeyFingerprinter.fingerprint(userApiKey);
        apiUsageLogService.record("GEMINI", 0, "BYOK_FAILED", fingerprint);
        response = geminiClient.callStructured(request, null);
        apiUsageLogService.record("GEMINI", 0, "SERVER_POOL_FALLBACK", null);
      } else {
        apiUsageLogService.record("GEMINI", 0, "BYOK", KeyFingerprinter.fingerprint(userApiKey));
      }
    } else {
      response = geminiClient.callStructured(request, null);
      apiUsageLogService.record("GEMINI", 0, "SERVER_POOL", null);
    }
    return response.claims().stream().map(this::applyValidators).toList();
  }

  /**
   * Schema Validator 더하기 Tier3ReasonValidator 를 순서대로 적용해 claimStatusCandidate 를 갱신한다.
   *
   * <p>Validator &gt; Gemini priority 원칙: schema 실패 시 INSUFFICIENT_CANDIDATE 강제, tier3Validator 결과가
   * 있으면 해당 candidate 로 override.
   *
   * @param draft Gemini 가 생산한 원본 ClaimDraft
   * @return claimStatusCandidate 가 갱신된 ClaimDraft (변경 없으면 원본 반환)
   */
  private ClaimDraft applyValidators(ClaimDraft draft) {
    // Schema 실패 시 INSUFFICIENT_CANDIDATE 강제
    if (!schemaValidator.isValid(draft)) {
      return withStatus(draft, ClaimStatusCandidate.INSUFFICIENT_CANDIDATE);
    }
    // Tier3ReasonValidator (Validator > Gemini priority)
    return tier3Validator
        .validate(draft)
        .map(reason -> withStatus(draft, mapToCandidate(reason)))
        .orElse(draft);
  }

  /**
   * claimStatusCandidate 만 교체한 새 ClaimDraft 를 반환한다. record 불변 계약 유지.
   *
   * @param original 원본 ClaimDraft
   * @param newStatus 갱신할 ClaimStatusCandidate
   * @return 나머지 필드는 동일하고 claimStatusCandidate 만 newStatus 로 교체된 ClaimDraft
   */
  private ClaimDraft withStatus(ClaimDraft original, ClaimStatusCandidate newStatus) {
    return new ClaimDraft(
        original.claimId(),
        original.claimText(),
        original.speakerName(),
        original.isQuotedClaim(),
        original.originalContext(),
        newStatus,
        original.splitGroup());
  }

  /**
   * HeuristicValidator.Tier3ReasonCandidate 를 ClaimStatusCandidate 로 변환한다.
   *
   * @param reason 휴리스틱 또는 Gemini candidate 판정 결과
   * @return 대응하는 ClaimStatusCandidate
   */
  private ClaimStatusCandidate mapToCandidate(HeuristicValidator.Tier3ReasonCandidate reason) {
    return switch (reason) {
      case OUT_OF_SCOPE -> ClaimStatusCandidate.OUT_OF_SCOPE_CANDIDATE;
      case TIME_SENSITIVE -> ClaimStatusCandidate.TIME_SENSITIVE_CANDIDATE;
      case INSUFFICIENT -> ClaimStatusCandidate.INSUFFICIENT_CANDIDATE;
    };
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
