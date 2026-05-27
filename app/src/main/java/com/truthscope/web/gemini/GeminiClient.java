package com.truthscope.web.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truthscope.web.entity.enums.DecisionSource;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Gemini API 클라이언트 — production 프로파일 전용.
 *
 * <p>PLAN §4-1 rev.5 amend 정합:
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
   * <p>PRIMARY 모델로 시도, CircuitBreaker 개입 또는 오류 시 {@link #fallbackStructured} 로 위임.
   *
   * @param req Gemini 요청 DTO
   * @return 정규화된 응답
   */
  @CircuitBreaker(name = "gemini", fallbackMethod = "fallbackStructured")
  public GeminiResponse callStructured(GeminiRequest req) {
    GeminiGenerateContentResponse wrapper =
        restClient
            .post()
            .uri("/v1beta/models/{model}:generateContent", PRIMARY_MODEL)
            .header("x-goog-api-key", apiKey)
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
   * CircuitBreaker fallback — status 분기 처리 (rev.5 amend Round 4 CX4-2).
   *
   * <ul>
   *   <li>{@link CallNotPermittedException} → CB open 상태 → {@link DecisionSource#CIRCUIT_BREAKER}
   *   <li>{@link HttpClientErrorException} 4xx (400/401/403) → Gemini 측 거부 → {@link
   *       DecisionSource#GEMINI} (운영 의미 보존)
   *   <li>1차 5xx/429/timeout → 2차 {@link #FALLBACK_MODEL} 재시도 → 성공 시 {@link DecisionSource#GEMINI}
   *   <li>2차 모델도 실패 → {@link DecisionSource#HEURISTIC_FALLBACK}
   * </ul>
   *
   * @param req 원본 요청
   * @param throwable callStructured 에서 전파된 예외
   * @return 정규화된 응답
   */
  public GeminiResponse fallbackStructured(GeminiRequest req, Throwable throwable) {
    if (throwable instanceof CallNotPermittedException) {
      return GeminiResponse.insufficient(DecisionSource.CIRCUIT_BREAKER);
    }

    if (throwable instanceof HttpClientErrorException httpEx) {
      int statusCode = httpEx.getStatusCode().value();
      if (statusCode == 400 || statusCode == 401 || statusCode == 403) {
        return GeminiResponse.insufficient(DecisionSource.GEMINI);
      }
    }

    // 1차 5xx / 429 / timeout — 2차 FALLBACK_MODEL 재시도
    try {
      GeminiGenerateContentResponse fallbackWrapper =
          restClient
              .post()
              .uri("/v1beta/models/{model}:generateContent", FALLBACK_MODEL)
              .header("x-goog-api-key", apiKey)
              .body(req)
              .retrieve()
              .body(GeminiGenerateContentResponse.class);

      return parseResponse(fallbackWrapper);
    } catch (RuntimeException | JsonProcessingException ex) {
      return GeminiResponse.insufficient(DecisionSource.HEURISTIC_FALLBACK);
    }
  }

  /**
   * wrapper 응답을 2단계 파싱하여 {@link GeminiResponse} 반환.
   *
   * <p>empty candidates, SAFETY blockReason, parts[0].text 파싱 실패 등 비정상 응답은 {@link
   * GeminiResponse#insufficient} 로 처리.
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

    // promptFeedback.blockReason 확인
    if (wrapper.promptFeedback() != null && wrapper.promptFeedback().blockReason() != null) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    // empty candidates 확인
    if (wrapper.candidates() == null || wrapper.candidates().isEmpty()) {
      return GeminiResponse.insufficient(DecisionSource.GEMINI);
    }

    GeminiGenerateContentResponse.Candidate candidate = wrapper.candidates().get(0);

    // SAFETY finishReason 확인
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

    // 2단계 파싱: parts[0].text → ClaimAnalysisPayload
    ClaimAnalysisPayload payload = objectMapper.readValue(text, ClaimAnalysisPayload.class);
    return GeminiResponse.from(payload, wrapper);
  }
}
