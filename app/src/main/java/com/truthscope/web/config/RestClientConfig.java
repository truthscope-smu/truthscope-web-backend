package com.truthscope.web.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient Bean 설정 — Gemini API 전용 + URL 유효성 검증 전용 2 Bean 분리.
 *
 * <ul>
 *   <li>{@link #geminiRestClient} — Gemini API 전용. connectTimeout 5s, readTimeout 15s.
 *   <li>{@link #urlValidatorRestClient} — URL 유효성 검증 전용. connectTimeout 5s, readTimeout 5s,
 *       redirect NEVER (수동 Location 검증 정합).
 * </ul>
 *
 * <p>PLAN §4-1 결정 CX2-3, CX2-4, R2-4 정합.
 */
@Configuration
public class RestClientConfig {

  /**
   * Gemini API 전용 RestClient.
   *
   * <p>baseUrl 기본값 {@code https://generativelanguage.googleapis.com}. WireMock cassette 통합 테스트에서
   * {@code truthscope.gemini.base-url} property로 override.
   */
  @Bean
  public RestClient geminiRestClient(
      @Value("${truthscope.gemini.base-url:https://generativelanguage.googleapis.com}")
          String baseUrl) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(15));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /**
   * URL 유효성 검증 전용 RestClient.
   *
   * <p>connectTimeout 5s, readTimeout 5s. 자동 redirect 추적 OFF ({@link HttpClient.Redirect#NEVER}) —
   * 수동 Location 헤더 검증 정합.
   */
  @Bean
  public RestClient urlValidatorRestClient() {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory).build();
  }
}
