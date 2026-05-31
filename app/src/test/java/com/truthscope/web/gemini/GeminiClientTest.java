package com.truthscope.web.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.truthscope.web.entity.enums.DecisionSource;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

/**
 * GeminiClient 단위 테스트.
 *
 * <p>RestClient 를 Mockito 로 mock 하여 네트워크 없이 PLAN §11-1 시나리오를 검증한다.
 *
 * <p>RestClient 체인: post() → uri() → header() → body() → retrieve() → body(Class)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GeminiClient 단위 테스트")
class GeminiClientTest {

  @Mock private RestClient restClient;
  @Mock private RequestBodyUriSpec requestBodyUriSpec;
  @Mock private RequestBodySpec requestBodySpec;
  @Mock private ResponseSpec responseSpec;

  private ObjectMapper objectMapper;
  private GeminiClient geminiClient;

  private static final GeminiRequest DUMMY_REQUEST =
      new GeminiRequest(
          List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part("테스트 기사")))),
          new GeminiRequest.GenerationConfig("application/json", 0.0));

  @BeforeEach
  void setUp() {
    // Java record 역직렬화 지원: ParameterNamesModule + USE_ANNOTATIONS + FAIL_ON_UNKNOWN_PROPERTIES
    objectMapper =
        JsonMapper.builder()
            .enable(MapperFeature.USE_ANNOTATIONS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new ParameterNamesModule())
            .build();
    geminiClient = new GeminiClient(restClient, "test-api-key", objectMapper);
  }

  /** 정상 GeminiGenerateContentResponse 생성 (단일 claim JSON). */
  private GeminiGenerateContentResponse buildSuccessWrapper(String claimText) {
    String payloadJson =
        "{"
            + "\"claims\":[{"
            + "\"claim_text\":\""
            + claimText
            + "\","
            + "\"speaker_name\":null,"
            + "\"is_quoted_claim\":false,"
            + "\"original_context\":null,"
            + "\"claim_status_candidate\":\"SCORABLE\","
            + "\"split_group\":null"
            + "}]"
            + "}";

    GeminiGenerateContentResponse.Part part = new GeminiGenerateContentResponse.Part(payloadJson);
    GeminiGenerateContentResponse.Content content =
        new GeminiGenerateContentResponse.Content(List.of(part), "model");
    GeminiGenerateContentResponse.Candidate candidate =
        new GeminiGenerateContentResponse.Candidate(content, "STOP", 0);
    return new GeminiGenerateContentResponse(List.of(candidate), null, null);
  }

  /**
   * RestClient 체인 stubbing.
   *
   * <p>post() → uri(str, Object...) → header(str, String...) → body(obj) → retrieve() → body(Class)
   *
   * <p>header() 는 varargs (String...) 이므로 두 번째 인자는 {@code (String[]) any()} 로 매칭한다.
   */
  private void stubRestClient(GeminiGenerateContentResponse response) {
    when(restClient.post()).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString(), any(Object[].class));
    doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), any(String[].class));
    doReturn(requestBodySpec).when(requestBodySpec).contentType(any());
    doReturn(requestBodySpec).when(requestBodySpec).accept(any());
    doReturn(requestBodySpec).when(requestBodySpec).body(any(Object.class));
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doReturn(response).when(responseSpec).body(GeminiGenerateContentResponse.class);
  }

  @Test
  @DisplayName("1순위_모델_호출_성공시_GeminiResponse_claims_파싱_통과")
  void primaryModel_호출_성공_claims_반환() {
    GeminiGenerateContentResponse wrapper = buildSuccessWrapper("정부는 GDP 성장률 3%를 발표했다.");
    stubRestClient(wrapper);

    GeminiResponse response = geminiClient.callStructured(DUMMY_REQUEST, null);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).hasSize(1);
    assertThat(response.claims().get(0).claimText()).isEqualTo("정부는 GDP 성장률 3%를 발표했다.");
    assertThat(response.claims().get(0).claimStatusCandidate())
        .isEqualTo(ClaimStatusCandidate.SCORABLE);
  }

  @Test
  @DisplayName("CallNotPermittedException_발생시_CIRCUIT_BREAKER_insufficient_반환")
  void circuitBreaker_open_circuitBreakerSource_반환() {
    // CircuitBreaker mock 으로 NPE 없이 CallNotPermittedException 생성
    CircuitBreaker cbMock = mock(CircuitBreaker.class);
    when(cbMock.getCircuitBreakerConfig())
        .thenReturn(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults());
    CallNotPermittedException cbException =
        CallNotPermittedException.createCallNotPermittedException(cbMock);

    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, cbException);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.CIRCUIT_BREAKER);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("HttpClientErrorException_400_발생시_GEMINI_insufficient_반환")
  void httpClientError_400_GEMINI_insufficient_반환() {
    HttpClientErrorException badRequest =
        HttpClientErrorException.create(
            org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);

    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, badRequest);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("HttpClientErrorException_403_발생시_GEMINI_insufficient_반환")
  void httpClientError_403_GEMINI_insufficient_반환() {
    HttpClientErrorException forbidden =
        HttpClientErrorException.create(
            org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null);

    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, forbidden);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("1차_5xx_예외_fallback_2순위_모델_성공시_GEMINI_반환")
  void serverError_fallback_2ndModel_성공() {
    GeminiGenerateContentResponse fallbackWrapper = buildSuccessWrapper("폴백 모델이 추출한 claim");

    // fallbackStructured 내부에서 restClient 체인 재호출 → 동일 mock stubbing
    when(restClient.post()).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString(), any(Object[].class));
    doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), any(String[].class));
    doReturn(requestBodySpec).when(requestBodySpec).contentType(any());
    doReturn(requestBodySpec).when(requestBodySpec).accept(any());
    doReturn(requestBodySpec).when(requestBodySpec).body(any(Object.class));
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doReturn(fallbackWrapper).when(responseSpec).body(GeminiGenerateContentResponse.class);

    RuntimeException serverError = new RuntimeException("503 Service Unavailable");
    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, serverError);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).hasSize(1);
    assertThat(response.claims().get(0).claimText()).isEqualTo("폴백 모델이 추출한 claim");
  }

  @Test
  @DisplayName("2순위_모델도_실패시_HEURISTIC_FALLBACK_insufficient_반환")
  void bothModels_실패_HEURISTIC_FALLBACK_반환() {
    when(restClient.post()).thenThrow(new RuntimeException("네트워크 불가"));

    RuntimeException primaryError = new RuntimeException("1차 5xx 오류");
    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, primaryError);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.HEURISTIC_FALLBACK);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("정상_응답_promptFeedback_blockReason_not_null시_GEMINI_insufficient_반환")
  void safetyBlock_blockReason_GEMINI_insufficient_반환() {
    GeminiGenerateContentResponse.PromptFeedback blocked =
        new GeminiGenerateContentResponse.PromptFeedback("HARM_CATEGORY_DANGEROUS_CONTENT", null);
    GeminiGenerateContentResponse.Part part =
        new GeminiGenerateContentResponse.Part("{\"claims\":[]}");
    GeminiGenerateContentResponse.Content content =
        new GeminiGenerateContentResponse.Content(List.of(part), "model");
    GeminiGenerateContentResponse.Candidate candidate =
        new GeminiGenerateContentResponse.Candidate(content, "STOP", 0);
    GeminiGenerateContentResponse wrapper =
        new GeminiGenerateContentResponse(List.of(candidate), blocked, null);

    stubRestClient(wrapper);

    GeminiResponse response = geminiClient.callStructured(DUMMY_REQUEST, null);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("정상_응답_finishReason_SAFETY시_GEMINI_insufficient_반환")
  void finishReason_SAFETY_GEMINI_insufficient_반환() {
    GeminiGenerateContentResponse.Part part =
        new GeminiGenerateContentResponse.Part("{\"claims\":[]}");
    GeminiGenerateContentResponse.Content content =
        new GeminiGenerateContentResponse.Content(List.of(part), "model");
    GeminiGenerateContentResponse.Candidate candidate =
        new GeminiGenerateContentResponse.Candidate(content, "SAFETY", 0);
    GeminiGenerateContentResponse wrapper =
        new GeminiGenerateContentResponse(List.of(candidate), null, null);

    stubRestClient(wrapper);

    GeminiResponse response = geminiClient.callStructured(DUMMY_REQUEST, null);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("parts_텍스트가_유효하지_않은_JSON이면_JsonProcessingException_catch_GEMINI_insufficient_반환")
  void invalidJson_parts_텍스트_GEMINI_insufficient_반환() {
    GeminiGenerateContentResponse.Part part =
        new GeminiGenerateContentResponse.Part("not-valid-json");
    GeminiGenerateContentResponse.Content content =
        new GeminiGenerateContentResponse.Content(List.of(part), "model");
    GeminiGenerateContentResponse.Candidate candidate =
        new GeminiGenerateContentResponse.Candidate(content, "STOP", 0);
    GeminiGenerateContentResponse wrapper =
        new GeminiGenerateContentResponse(List.of(candidate), null, null);

    stubRestClient(wrapper);

    GeminiResponse response = geminiClient.callStructured(DUMMY_REQUEST, null);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).isEmpty();
  }

  @Test
  @DisplayName("ResourceAccessException_timeout_fallback_2순위_성공시_GEMINI_반환")
  void timeout_ResourceAccessException_fallback_성공() {
    GeminiGenerateContentResponse fallbackWrapper = buildSuccessWrapper("타임아웃 후 폴백 claim");

    when(restClient.post()).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString(), any(Object[].class));
    doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), any(String[].class));
    doReturn(requestBodySpec).when(requestBodySpec).contentType(any());
    doReturn(requestBodySpec).when(requestBodySpec).accept(any());
    doReturn(requestBodySpec).when(requestBodySpec).body(any(Object.class));
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doReturn(fallbackWrapper).when(responseSpec).body(GeminiGenerateContentResponse.class);

    ResourceAccessException timeout = new ResourceAccessException("Read timed out");
    GeminiResponse response = geminiClient.fallbackStructured(DUMMY_REQUEST, null, timeout);

    assertThat(response.decisionSource()).isEqualTo(DecisionSource.GEMINI);
    assertThat(response.claims()).hasSize(1);
  }
}
