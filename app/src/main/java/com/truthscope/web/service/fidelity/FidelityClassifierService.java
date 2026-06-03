package com.truthscope.web.service.fidelity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truthscope.web.audit.KeyFingerprinter;
import com.truthscope.web.gemini.FidelityItem;
import com.truthscope.web.gemini.FidelityPromptShield;
import com.truthscope.web.gemini.GeminiGenerateContentResponse;
import com.truthscope.web.gemini.GeminiRequest;
import com.truthscope.web.scoring.EvidenceCandidate;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.FidelityClassifierPort;
import com.truthscope.web.service.audit.ApiUsageLogService;
import jakarta.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Gemini 기반 충실성 분류기 — production 프로파일 전용.
 *
 * <p>claimText 와 공식 원문 후보 목록을 Gemini 에 전달하여 충실성(stance)을 판정하고 {@link EvidenceSnapshot} 목록을 반환한다.
 *
 * <p>BYOK 분기는 {@code ClaimAnalysisService:58-74} 동일 패턴 (인증 실패 시 서버 기본 키 재시도). 실패/빈응답/safety/circuit
 * open → 빈 List (Tier 3 안전강하). {@code @Transactional} 금지 — 외부 HTTP 호출 포함 (RC-01 정합).
 *
 * <p>관련성 필터 (codex Round 2 조건 1): stance ∈ {SUPPORTED, CONTRADICTED} 이고 matchedFields 비어 있지 않은 후보만
 * 반환. NEUTRAL/UNRELATED/0-match 는 evidence 카운트 제외 → PolicyEvidenceScorer 0점 SCORABLE 차단.
 */
@Service
@Profile("production")
public class FidelityClassifierService implements FidelityClassifierPort {

  private static final Logger log = LoggerFactory.getLogger(FidelityClassifierService.class);
  private static final String PRIMARY_MODEL = "gemini-3.1-flash-lite";
  private static final String AUDIT_TAG = "GEMINI_FIDELITY";

  private final RestClient restClient;
  private final String serverApiKey;
  private final FidelityPromptShield promptShield;
  private final ApiUsageLogService apiUsageLogService;
  private final ObjectMapper objectMapper;

  public FidelityClassifierService(
      @Qualifier("geminiRestClient") RestClient restClient,
      @Value("${truthscope.gemini.api-key:}") String serverApiKey,
      FidelityPromptShield promptShield,
      ApiUsageLogService apiUsageLogService,
      ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.serverApiKey = serverApiKey;
    this.promptShield = promptShield;
    this.apiUsageLogService = apiUsageLogService;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<EvidenceSnapshot> classify(
      String claimText, List<EvidenceCandidate> candidates, @Nullable String userApiKey) {
    if (claimText == null || claimText.isBlank() || candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    String candidatesBlock = renderCandidatesBlock(candidates);
    String prompt = promptShield.assemble(claimText, candidatesBlock);
    GeminiRequest request = buildRequest(prompt);

    if (userApiKey != null && !userApiKey.isBlank()) {
      return classifyWithByok(request, userApiKey);
    } else {
      return classifyWithServerKey(request);
    }
  }

  /** BYOK 분기 — 인증 실패 시 서버 기본 키로 재시도 (ADR-004 §f 정합). */
  private List<EvidenceSnapshot> classifyWithByok(GeminiRequest request, String userApiKey) {
    try {
      List<EvidenceSnapshot> result = callAndParse(request, userApiKey);
      apiUsageLogService.record(AUDIT_TAG, 0, "BYOK", KeyFingerprinter.fingerprint(userApiKey));
      return applyRelevanceFilter(result);
    } catch (HttpClientErrorException httpEx) {
      HttpStatusCode status = httpEx.getStatusCode();
      if (status.value() == 401 || status.value() == 403) {
        return fallbackToServerKey(request, userApiKey);
      }
      log.warn(
          "FidelityClassifier: HTTP {} — Tier 3 안전강하. cause={}",
          status.value(),
          httpEx.getMessage());
      return List.of();
    } catch (Exception ex) {
      log.warn("FidelityClassifier: BYOK 호출 실패 — Tier 3 안전강하. cause={}", ex.getMessage());
      return List.of();
    }
  }

  /** BYOK 인증 실패 후 서버 기본 키로 재시도. */
  private List<EvidenceSnapshot> fallbackToServerKey(GeminiRequest request, String userApiKey) {
    apiUsageLogService.record(
        AUDIT_TAG, 0, "BYOK_FAILED", KeyFingerprinter.fingerprint(userApiKey));
    try {
      List<EvidenceSnapshot> fallback = callAndParse(request, null);
      apiUsageLogService.record(AUDIT_TAG, 0, "SERVER_POOL_FALLBACK", null);
      return applyRelevanceFilter(fallback);
    } catch (Exception fallbackEx) {
      log.warn(
          "FidelityClassifier: 서버 기본 키 재시도 실패 — Tier 3 안전강하. cause={}", fallbackEx.getMessage());
      return List.of();
    }
  }

  /** 서버 기본 키 분기. */
  private List<EvidenceSnapshot> classifyWithServerKey(GeminiRequest request) {
    try {
      List<EvidenceSnapshot> result = callAndParse(request, null);
      apiUsageLogService.record(AUDIT_TAG, 0, "SERVER_POOL", null);
      return applyRelevanceFilter(result);
    } catch (Exception ex) {
      log.warn("FidelityClassifier: 서버 키 호출 실패 — Tier 3 안전강하. cause={}", ex.getMessage());
      return List.of();
    }
  }

  /**
   * Gemini API 를 직접 호출하여 {@link GeminiGenerateContentResponse} wrapper 를 받고 2단계 파싱으로 {@link
   * FidelityPayload} 를 반환한다.
   *
   * <p>2단계 파싱: wrapper null/safety/candidates 검사 → parts[0].text 추출 → objectMapper.readValue(text,
   * FidelityPayload.class).
   *
   * @param request Gemini 요청
   * @param apiKey 실제 사용할 API 키 (null → 서버 기본 키)
   * @return 파싱된 EvidenceSnapshot 목록 (관련성 필터 적용 전)
   * @throws Exception HTTP 에러, parse 실패 등 (호출부에서 catch)
   */
  private List<EvidenceSnapshot> callAndParse(GeminiRequest request, @Nullable String apiKey)
      throws Exception {
    String effectiveKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : serverApiKey;

    GeminiGenerateContentResponse wrapper =
        restClient
            .post()
            .uri("/v1beta/models/{model}:generateContent", PRIMARY_MODEL)
            .header("x-goog-api-key", effectiveKey)
            // jackson-dataformat-xml(data.go.kr용)이 classpath에 있어 contentType 미지정 시 요청이 XML로
            // 직렬화됨 → Gemini 400. JSON 강제 (GeminiClient와 동일 처리).
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(GeminiGenerateContentResponse.class);

    return parseGeminiResponse(wrapper);
  }

  /**
   * GeminiGenerateContentResponse 를 파싱하여 EvidenceSnapshot 목록을 반환한다.
   *
   * <p>wrapper null/safety/candidates 검사 → parts[0].text 추출 → JSON 배열 역직렬화.
   */
  private List<EvidenceSnapshot> parseGeminiResponse(GeminiGenerateContentResponse wrapper)
      throws Exception {
    if (wrapper == null) {
      return List.of();
    }
    if (wrapper.promptFeedback() != null && wrapper.promptFeedback().blockReason() != null) {
      return List.of();
    }
    if (wrapper.candidates() == null || wrapper.candidates().isEmpty()) {
      return List.of();
    }

    GeminiGenerateContentResponse.Candidate candidate = wrapper.candidates().get(0);
    if ("SAFETY".equals(candidate.finishReason())) {
      return List.of();
    }
    if (candidate.content() == null
        || candidate.content().parts() == null
        || candidate.content().parts().isEmpty()) {
      return List.of();
    }

    String text = candidate.content().parts().get(0).text();
    if (text == null || text.isBlank()) {
      return List.of();
    }

    List<FidelityItem> items;
    try {
      items = objectMapper.readValue(text.trim(), new TypeReference<List<FidelityItem>>() {});
    } catch (Exception parseEx) {
      log.warn(
          "FidelityClassifier: FidelityPayload 파싱 실패 — 빈 결과 반환. cause={}", parseEx.getMessage());
      return List.of();
    }

    if (items == null || items.isEmpty()) {
      return List.of();
    }

    return items.stream()
        .filter(item -> item.url() != null && item.stance() != null)
        .map(this::toSnapshot)
        .toList();
  }

  /**
   * {@link FidelityItem} 을 {@link EvidenceSnapshot} 으로 변환한다.
   *
   * <p>stance 소문자 (Gemini 출력) → 대문자 (EvidenceSnapshot 규약): supports → SUPPORTED, refutes →
   * CONTRADICTED, neutral → NEUTRAL. summary 는 EvidenceSnapshot 필드 불변 계약에 따라 폐기 (converter 에서 도출).
   * mismatchedFields 는 Gemini 응답에서 null 이면 emptyMap() 으로 대체 (레거시 응답 호환).
   */
  private EvidenceSnapshot toSnapshot(FidelityItem item) {
    String normalized = item.stance() != null ? item.stance().trim().toLowerCase(Locale.ROOT) : "";
    String stance =
        switch (normalized) {
          case "supports" -> "SUPPORTED";
          case "refutes" -> "CONTRADICTED";
          default -> "NEUTRAL";
        };
    Map<String, String> matchedFields =
        item.matchedFields() != null ? item.matchedFields() : Collections.emptyMap();
    Map<String, String> mismatchedFields =
        item.mismatchedFields() != null ? item.mismatchedFields() : Collections.emptyMap();
    return new EvidenceSnapshot(
        item.url(), item.publisher(), item.title(), stance, matchedFields, mismatchedFields);
  }

  /**
   * 관련성 필터 (codex Round 2 조건 1 MANDATORY): stance ∈ {SUPPORTED, CONTRADICTED} 이고 matchedFields 비어
   * 있지 않은 후보만 통과. NEUTRAL/UNRELATED/0-match 는 evidence 카운트에서 제외.
   */
  private List<EvidenceSnapshot> applyRelevanceFilter(List<EvidenceSnapshot> snapshots) {
    return snapshots.stream()
        .filter(
            s ->
                ("SUPPORTED".equals(s.stance()) || "CONTRADICTED".equals(s.stance()))
                    && s.matchedFields() != null
                    && !s.matchedFields().isEmpty())
        .toList();
  }

  /** 후보 목록을 Gemini 프롬프트용 텍스트 블록으로 렌더링한다. */
  private String renderCandidatesBlock(List<EvidenceCandidate> candidates) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < candidates.size(); i++) {
      EvidenceCandidate c = candidates.get(i);
      sb.append("[후보 ").append(i + 1).append("]\n");
      sb.append("url: ").append(c.url() != null ? c.url() : "").append("\n");
      sb.append("publisher: ").append(c.publisher() != null ? c.publisher() : "").append("\n");
      sb.append("title: ").append(c.title() != null ? c.title() : "").append("\n");
      sb.append("body: ").append(c.body() != null ? c.body() : "").append("\n\n");
    }
    return sb.toString().trim();
  }

  private GeminiRequest buildRequest(String prompt) {
    return new GeminiRequest(
        List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part(prompt)))),
        new GeminiRequest.GenerationConfig("application/json", 0.0));
  }
}
