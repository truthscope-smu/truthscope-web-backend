package com.truthscope.web.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truthscope.web.entity.enums.DecisionSource;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Gemini API 클라이언트 — production 프로파일 전용.
 *
 * <p>BE #74 amend: BYOK overrideApiKey 인자 추가. null/blank → 서버 기본 키 사용, 명시 → 사용자 키 1회성 사용.
 * fallbackStructured 내부 2차 FALLBACK_MODEL 호출도 effectiveKey 사용 (this.apiKey 하드코딩 금지). BYOK 401/403 →
 * {@link GeminiResponse#authFailed()} 신호 (서버 키 fallback 트리거).
 *
 * <ul>
 *   <li>{@link #PRIMARY_MODEL} = {@code gemini-3.1-flash-lite} (domain-logic.md lock, 2026-05-27 GA
 *       전환으로 -preview 제거)
 *   <li>{@link #FALLBACK_MODEL} = {@code gemini-2.5-flash-lite} (domain-logic.md lock)
 *   <li>2단계 파싱: {@link GeminiGenerateContentResponse} wrapper 역직렬화 → {@code parts[0].text} 추출 →
 *       {@link ClaimAnalysisPayload} 역직렬화 (rev.5 amend Round 4 CX4-1)
 *   <li>CircuitBreaker name = "gemini", fallback = {@link #fallbackStructured} (CX4-2 status 분기)
 * </ul>
 *
 * <p>Lombok {@code @RequiredArgsConstructor} 사용 금지 — {@code @Qualifier} 명시 constructor 의무 (rev.4
 * amend Round 3 CX3-2).
 */
@Component
@Profile("production")
public class GeminiClient {

  private static final String PRIMARY_MODEL = "gemini-3.1-flash-lite";
  private static final String FALLBACK_MODEL = "gemini-2.5-flash-lite";

  private final RestClient restClient;
  private final String apiKey;
  private final ObjectMapper objectMapper;

  public GeminiClient(
      @Qualifier("geminiRestClient") RestClient restClient,
      @Value("${truthscope.gemini.api-key:}") String apiKey,
      ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
  }

  /**
   * Gemini API 에 structured output 요청을 전송하고 {@link GeminiResponse} 를 반환.
   *
   * @param req Gemini 요청 DTO
   * @param overrideApiKey BYOK 사용자 키 (null/blank → 서버 기본 키 사용)
   * @return 정규화된 응답
   */
  @CircuitBreaker(name = "gemini", fallbackMethod = "fallbackStructured")
  public GeminiResponse callStructured(GeminiRequest req, @Nullable String overrideApiKey) {
    String effectiveKey = resolveEffectiveKey(overrideApiKey);
    GeminiGenerateContentResponse wrapper =
        restClient
            .post()
            .uri("/v1beta/models/{model}:generateContent", PRIMARY_MODEL)
            .header("x-goog-api-key", effectiveKey)
            // jackson-dataformat-xml(data.go.kr용)이 classpath에 있어 contentType 미지정 시 요청이 XML로
            // 직렬화됨 → Gemini 400. JSON 강제 (response는 application/json 으로 정상 수신).
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(req)
            .retrieve()
            .body(GeminiGenerateContentResponse.class);

    try {
      return parseResponse(wrapper);
    } catch (JsonProcessingException ex) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }
  }

  /**
   * CircuitBreaker fallback — status 분기 처리.
   *
   * <ul>
   *   <li>{@link CallNotPermittedException} → CB open → {@link DecisionSource#CIRCUIT_BREAKER}
   *   <li>BYOK 호출 시 401/403 → {@link GeminiResponse#authFailed()} (서버 키 fallback 트리거)
   *   <li>서버 기본 키 4xx 또는 400 (요청 body 오류, BYOK 무관) → {@link DecisionSource#GEMINI} insufficient
   *   <li>1차 5xx/429/timeout → 2차 {@link #FALLBACK_MODEL} 재시도 + effectiveKey 사용
   *   <li>2차 모델도 실패 → {@link DecisionSource#HEURISTIC_FALLBACK}
   * </ul>
   *
   * @param req 원본 요청
   * @param overrideApiKey BYOK 사용자 키 (null → 서버 기본)
   * @param throwable callStructured 에서 전파된 예외
   * @return 정규화된 응답
   */
  public GeminiResponse fallbackStructured(
      GeminiRequest req, @Nullable String overrideApiKey, Throwable throwable) {
    if (throwable instanceof CallNotPermittedException) {
      return GeminiResponse.insufficient(DecisionSource.CIRCUIT_BREAKER);
    }

    if (throwable instanceof HttpClientErrorException httpEx) {
      int statusCode = httpEx.getStatusCode().value();
      // BE #74 amend: BYOK 키 사용 중 401/403 → authFailed 신호 (서버 키 fallback 트리거)
      if ((statusCode == 401 || statusCode == 403)
          && overrideApiKey != null
          && !overrideApiKey.isBlank()) {
        return GeminiResponse.authFailed();
      }
      // 400 (요청 body 오류, BYOK 무관) + 서버 키 401/403 → 일반 GEMINI insufficient
      if (statusCode == 400 || statusCode == 401 || statusCode == 403) {
        return GeminiResponse.insufficient(DecisionSource.GEMINI);
      }
    }

    // 1차 5xx/429/timeout — 2차 FALLBACK_MODEL 재시도. effectiveKey 사용 (this.apiKey 하드코딩 금지)
    String effectiveKey = resolveEffectiveKey(overrideApiKey);
    try {
      GeminiGenerateContentResponse fallbackWrapper =
          restClient
              .post()
              .uri("/v1beta/models/{model}:generateContent", FALLBACK_MODEL)
              .header("x-goog-api-key", effectiveKey)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(req)
              .retrieve()
              .body(GeminiGenerateContentResponse.class);

      return parseResponse(fallbackWrapper);
    } catch (RuntimeException | JsonProcessingException ex) {
      return GeminiResponse.insufficient(DecisionSource.HEURISTIC_FALLBACK);
    }
  }

  /**
   * BYOK overrideApiKey가 있으면 사용, 없으면 서버 기본 키 (CodeRabbit refactor — 중복 계산 헬퍼).
   *
   * @param overrideApiKey BYOK 사용자 키 (null/blank → 서버 기본)
   * @return 실제 사용할 키
   */
  private String resolveEffectiveKey(@Nullable String overrideApiKey) {
    return (overrideApiKey != null && !overrideApiKey.isBlank()) ? overrideApiKey : this.apiKey;
  }

  /**
   * wrapper 응답을 2단계 파싱하여 {@link GeminiResponse} 반환.
   *
   * @param wrapper Gemini API 응답 wrapper (nullable)
   * @return 정규화된 응답
   * @throws JsonProcessingException 2단계 파싱 실패 시 (호출부에서 catch)
   */
  private GeminiResponse parseResponse(GeminiGenerateContentResponse wrapper)
      throws JsonProcessingException {
    if (wrapper == null) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    if (wrapper.promptFeedback() != null && wrapper.promptFeedback().blockReason() != null) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    if (wrapper.candidates() == null || wrapper.candidates().isEmpty()) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    GeminiGenerateContentResponse.Candidate candidate = wrapper.candidates().get(0);

    if ("SAFETY".equals(candidate.finishReason())) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    if (candidate.content() == null
        || candidate.content().parts() == null
        || candidate.content().parts().isEmpty()) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    String text = candidate.content().parts().get(0).text();
    if (text == null || text.isBlank()) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    ClaimAnalysisPayload payload = objectMapper.readValue(text, ClaimAnalysisPayload.class);
    return GeminiResponse.from(payload, wrapper);
  }
}
