package com.truthscope.web.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.truthscope.web.scoring.UrlValidatorPolicy;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

/**
 * UrlValidator 단위 테스트.
 *
 * <p>RestClient 를 Mockito 로 mock 하여 네트워크 없이 HEAD 검증 시나리오를 검증한다.
 *
 * <p>RestClient 체인: method(HEAD) → uri(url) → retrieve() → toBodilessEntity() <br>
 * 타입 맵핑: method(HEAD) → RequestBodyUriSpec, uri(String) → RequestBodySpec, retrieve() →
 * ResponseSpec, toBodilessEntity() → ResponseEntity<Void>
 *
 * <p>Spring Context 없이 순수 단위 테스트. {@link UrlValidatorPolicy} 는 직접 생성 (T2-5 Bean 불필요).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UrlValidator 단위 테스트")
class UrlValidatorTest {

  @Mock private RestClient restClient;
  @Mock private RequestBodyUriSpec requestBodyUriSpec;
  @Mock private RequestBodySpec requestBodySpec;
  @Mock private ResponseSpec responseSpec;

  private UrlValidatorPolicy policy;
  private UrlValidator urlValidator;

  private static final String VALID_URL = "https://example.com/article";

  @BeforeEach
  void setUp() {
    // T2-5 Bean 없이 직접 생성 (PLAN §T2-4 주석)
    policy =
        new UrlValidatorPolicy(
            Duration.ofSeconds(5), // connectTimeout
            Duration.ofSeconds(5), // readTimeout
            5, // redirectMaxDepth
            1, // retryCount
            Duration.ofMillis(10)); // retryBackoff (테스트에서는 짧게)
    urlValidator = new UrlValidator(restClient, policy);
  }

  /**
   * RestClient HEAD 체인 stubbing — 공통 helper.
   *
   * <p>chain: method(HEAD) → RequestBodyUriSpec → uri(anyString()) → RequestBodySpec → retrieve() →
   * ResponseSpec → toBodilessEntity() → response
   */
  private void stubHead(ResponseEntity<Void> response) {
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doReturn(response).when(responseSpec).toBodilessEntity();
  }

  private static ResponseEntity<Void> ok200() {
    return ResponseEntity.ok().build();
  }

  private static ResponseEntity<Void> redirect301(String location) {
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create(location));
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).headers(headers).build();
  }

  // ─── 시나리오 1: HEAD 200 OK → true ───

  @Test
  @DisplayName("HEAD_200_OK_true_반환")
  void head200Ok_true_반환() {
    stubHead(ok200());

    boolean result = urlValidator.validate(VALID_URL);

    assertThat(result).isTrue();
  }

  // ─── 시나리오 2: HEAD 404 → false ───

  @Test
  @DisplayName("HEAD_404_false_반환")
  void head404_false_반환() {
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
        .when(responseSpec)
        .toBodilessEntity();

    boolean result = urlValidator.validate(VALID_URL);

    assertThat(result).isFalse();
  }

  // ─── 시나리오 3: HEAD 500 → false ───

  @Test
  @DisplayName("HEAD_500_false_반환")
  void head500_false_반환() {
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null))
        .when(responseSpec)
        .toBodilessEntity();

    boolean result = urlValidator.validate(VALID_URL);

    assertThat(result).isFalse();
  }

  // ─── 시나리오 4: timeout (ResourceAccessException) → false ───

  @Test
  @DisplayName("ResourceAccessException_timeout_false_반환")
  void timeout_ResourceAccessException_false_반환() {
    // retryCount=1 이므로 2회 모두 타임아웃 → 최종 false
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    doThrow(new ResourceAccessException("Read timed out")).when(responseSpec).toBodilessEntity();

    boolean result = urlValidator.validate(VALID_URL);

    assertThat(result).isFalse();
  }

  // ─── 시나리오 5: redirect chain 깊이 3 → final 200 OK → true ───

  @Test
  @DisplayName("redirect_chain_깊이3_final_200_true_반환")
  void redirectChain_depth3_final200_true_반환() {
    // depth 0: url1 → 301 → url2
    // depth 1: url2 → 301 → url3
    // depth 2: url3 → 301 → finalUrl
    // depth 3: finalUrl → 200 OK (maxDepth=5 이므로 허용)
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();

    String url2 = "https://example.com/r2";
    String url3 = "https://example.com/r3";
    String finalUrl = "https://example.com/final";

    when(responseSpec.toBodilessEntity())
        .thenReturn(redirect301(url2)) // depth 0
        .thenReturn(redirect301(url3)) // depth 1
        .thenReturn(redirect301(finalUrl)) // depth 2
        .thenReturn(ok200()); // depth 3

    boolean result = urlValidator.validate("https://example.com/r1");

    assertThat(result).isTrue();
  }

  // ─── 시나리오 6: redirect chain 깊이 6 → false (depth exceeded) ───

  @Test
  @DisplayName("redirect_chain_깊이6_depth_exceeded_false_반환")
  void redirectChain_depth6_false_반환() {
    // maxDepth=5 이므로 depth=6 진입 시점에 false 반환 (ok 에 도달하지 않음)
    // depth 0→1→2→3→4→5 (6번 redirect) → depth 6 에서 차단
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();

    when(responseSpec.toBodilessEntity())
        .thenReturn(redirect301("https://example.com/r1"))
        .thenReturn(redirect301("https://example.com/r2"))
        .thenReturn(redirect301("https://example.com/r3"))
        .thenReturn(redirect301("https://example.com/r4"))
        .thenReturn(redirect301("https://example.com/r5"))
        .thenReturn(redirect301("https://example.com/r6"))
        .thenReturn(ok200()); // depth 6 에서 차단되므로 이 라인은 호출되지 않음

    boolean result = urlValidator.validate("https://example.com/start");

    assertThat(result).isFalse();
  }

  // ─── 추가: null/blank url → false ───

  @Test
  @DisplayName("null_url_false_반환")
  void nullUrl_false_반환() {
    assertThat(urlValidator.validate(null)).isFalse();
  }

  @Test
  @DisplayName("blank_url_false_반환")
  void blankUrl_false_반환() {
    assertThat(urlValidator.validate("  ")).isFalse();
  }

  // ─── 추가: 일시적 실패 후 재시도 성공 → true ───

  @Test
  @DisplayName("ResourceAccessException_1회_후_재시도_성공_true_반환")
  void timeout_1회_재시도_성공_true_반환() {
    // retryCount=1: 첫 번째 ResourceAccessException → 재시도 → 200 OK
    when(restClient.method(HttpMethod.HEAD)).thenReturn(requestBodyUriSpec);
    doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
    doReturn(responseSpec).when(requestBodySpec).retrieve();
    when(responseSpec.toBodilessEntity())
        .thenThrow(new ResourceAccessException("Read timed out"))
        .thenReturn(ok200());

    boolean result = urlValidator.validate(VALID_URL);

    assertThat(result).isTrue();
  }
}
