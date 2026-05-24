package com.truthscope.web.url;

import com.truthscope.web.scoring.UrlValidatorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * URL 유효성 검증 컴포넌트.
 *
 * <p>HEAD 요청으로 URL 접근 가능 여부를 검증하며 다음 경우 {@code false} 를 반환한다:
 *
 * <ul>
 *   <li>4xx/5xx HTTP 응답 (RestClientResponseException)
 *   <li>연결 타임아웃 또는 읽기 타임아웃 (ResourceAccessException)
 *   <li>redirect chain 깊이가 {@link UrlValidatorPolicy#redirectMaxDepth()} 를 초과할 때
 * </ul>
 *
 * <p>redirect 처리: {@code urlValidatorRestClient} Bean 은 자동 redirect 추적 OFF
 * ({@link java.net.http.HttpClient.Redirect#NEVER}) — 3xx 응답의 {@code Location} 헤더를 직접 읽어
 * 재귀적으로 검증한다. 깊이 카운터가 {@link UrlValidatorPolicy#redirectMaxDepth()} 에 도달하면 {@code false} 반환.
 *
 * <p>단일 재시도: {@link UrlValidatorPolicy#retryCount()} = 1 일 때 일시적 실패에 대해 1회 재시도.
 * 재시도 대기 시간은 {@link UrlValidatorPolicy#retryBackoff()} (기본값 1초).
 *
 * <p>PLAN §1 결정 #31 / CX4-4 amend 정합.
 */
@Component
public class UrlValidator {

  private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);

  private final RestClient restClient;
  private final UrlValidatorPolicy policy;

  /**
   * 명시적 constructor — {@code @Qualifier} 전파를 위해 Lombok {@code @RequiredArgsConstructor} 대신 사용.
   *
   * <p>Round 3 CX3-2 amend: Lombok {@code @Qualifier} 는 constructor 파라미터에 복사되지 않는다.
   *
   * @param restClient URL 검증 전용 RestClient (redirect NEVER, connectTimeout 5s, readTimeout 5s)
   * @param policy URL 검증 정책 (redirectMaxDepth, retryCount, retryBackoff)
   */
  public UrlValidator(
      @Qualifier("urlValidatorRestClient") RestClient restClient, UrlValidatorPolicy policy) {
    this.restClient = restClient;
    this.policy = policy;
  }

  /**
   * URL 에 HEAD 요청을 보내 유효성을 검증한다.
   *
   * @param url 검증 대상 URL (절대 URL, null 또는 blank 이면 {@code false} 반환)
   * @return 유효한 URL 이면 {@code true}, 그렇지 않으면 {@code false}
   */
  public boolean validate(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    return validateWithDepth(url, 0);
  }

  /**
   * redirect chain 깊이를 추적하며 HEAD 검증을 수행하는 내부 메서드.
   *
   * @param url 검증 대상 URL
   * @param depth 현재 redirect 깊이
   * @return 유효 여부
   */
  private boolean validateWithDepth(String url, int depth) {
    if (depth > policy.redirectMaxDepth()) {
      log.debug("UrlValidator: redirect 깊이 초과 (depth={}, max={}, url={})",
          depth, policy.redirectMaxDepth(), url);
      return false;
    }

    int maxAttempts = 1 + policy.retryCount();
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        var response =
            restClient
                .method(HttpMethod.HEAD)
                .uri(url)
                .retrieve()
                .toBodilessEntity();

        // 3xx redirect — Location 헤더 수동 추적
        if (response.getStatusCode().is3xxRedirection()) {
          var location = response.getHeaders().getLocation();
          if (location == null) {
            log.debug("UrlValidator: 3xx 응답에 Location 헤더 없음 (url={}, status={})",
                url, response.getStatusCode().value());
            return false;
          }
          return validateWithDepth(location.toString(), depth + 1);
        }

        // 2xx 정상
        return true;

      } catch (RestClientResponseException ex) {
        // 4xx / 5xx
        log.debug("UrlValidator: HTTP 오류 응답 (url={}, status={}, attempt={})",
            url, ex.getStatusCode().value(), attempt);
        return false;

      } catch (ResourceAccessException ex) {
        // 타임아웃 또는 연결 실패
        log.debug("UrlValidator: 연결/읽기 타임아웃 (url={}, attempt={})", url, attempt);
        if (attempt < maxAttempts - 1) {
          sleepBackoff();
        } else {
          return false;
        }
      }
    }

    return false;
  }

  private void sleepBackoff() {
    try {
      Thread.sleep(policy.retryBackoff().toMillis());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
