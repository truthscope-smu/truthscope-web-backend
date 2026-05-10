package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.truthscope.web.exception.BadRequestException;
import com.truthscope.web.exception.ExtractionFailedException;
import com.truthscope.web.exception.SsrfBlockedException;
import com.truthscope.web.security.PinnedDnsResolver;
import com.truthscope.web.security.SsrfGuard;
import com.truthscope.web.security.SsrfGuard.ValidatedTarget;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@DisplayName(
    "ContentExtractService unit (mock) - redirect/null/304/Location/IOException/javascript/pinning")
class ContentExtractServiceUnitTest {

  private SsrfGuard ssrfGuard;
  private ContentExtractService service;
  private CloseableHttpClient mockClient;

  @BeforeEach
  void setUp() {
    ssrfGuard = mock(SsrfGuard.class);
    mockClient = mock(CloseableHttpClient.class);
    service = Mockito.spy(new ContentExtractService(ssrfGuard));
    // CX-26: typed matchers - CloseableHttpClient.execute overload 모호성 회피
    Mockito.doReturn(mockClient)
        .when(service)
        .buildClient(any(InetAddress[].class), Mockito.anyString());
  }

  @Test
  @DisplayName("redirect target이 SSRF 차단 IP면 SsrfBlockedException")
  void redirectToBlockedIp() throws Exception {
    URI initialUri = URI.create("http://news.example.com/article");
    mockSsrfApprove("http://news.example.com/article", initialUri, "news.example.com", "8.8.8.8");
    when(ssrfGuard.validateAndResolve("http://127.0.0.1/admin"))
        .thenThrow(new SsrfBlockedException("내부 네트워크 주소는 차단되었습니다"));
    mockHttpResponse(301, "Location", "http://127.0.0.1/admin", null);

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(SsrfBlockedException.class);
  }

  @Test
  @DisplayName("응답 entity가 null이면 ExtractionFailedException")
  void nullEntity() throws Exception {
    mockSsrfApprove(
        "http://news.example.com/article",
        URI.create("http://news.example.com/article"),
        "news.example.com",
        "8.8.8.8");
    mockHttpResponse(204, null, null, null);

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("기사를 가져올 수 없습니다");
  }

  @Test
  @DisplayName("Location invalid URI면 ExtractionFailedException (CX-6)")
  void invalidLocationHeader() throws Exception {
    mockSsrfApprove(
        "http://news.example.com/article",
        URI.create("http://news.example.com/article"),
        "news.example.com",
        "8.8.8.8");
    mockHttpResponse(301, "Location", "ht!tp://malformed||url", null);

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(ExtractionFailedException.class);
  }

  @Test
  @DisplayName("304 Not Modified는 redirect 아님 → ExtractionFailedException (CX-5)")
  void status304NotRedirect() throws Exception {
    mockSsrfApprove(
        "http://news.example.com/article",
        URI.create("http://news.example.com/article"),
        "news.example.com",
        "8.8.8.8");
    mockHttpResponse(304, null, null, null);

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(ExtractionFailedException.class);
  }

  @Test
  @DisplayName("max redirects 초과 시 ExtractionFailedException (R3-1/CX-27)")
  void maxRedirectsExceeded() throws Exception {
    when(ssrfGuard.validateAndResolve(Mockito.anyString()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return new ValidatedTarget(
                  URI.create(url),
                  "news.example.com",
                  "http",
                  -1,
                  new InetAddress[] {InetAddress.getByName("8.8.8.8")});
            });
    when(mockClient.execute(
            any(ClassicHttpRequest.class),
            any(HttpClientContext.class),
            ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
        .thenAnswer(
            inv -> {
              HttpClientResponseHandler<?> handler = inv.getArgument(2);
              org.apache.hc.core5.http.message.BasicClassicHttpResponse response =
                  new org.apache.hc.core5.http.message.BasicClassicHttpResponse(301);
              response.addHeader(
                  "Location", "http://news.example.com/redirect" + System.nanoTime());
              return handler.handleResponse(response);
            });

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(ExtractionFailedException.class);
  }

  @Test
  @DisplayName("javascript: URL redirect는 BadRequestException (R2-8)")
  void redirectWithJavascriptScheme() throws Exception {
    URI initialUri = URI.create("http://news.example.com/article");
    mockSsrfApprove("http://news.example.com/article", initialUri, "news.example.com", "8.8.8.8");
    when(ssrfGuard.validateAndResolve("javascript:alert(1)"))
        .thenThrow(new BadRequestException("유효하지 않은 URL 형식입니다"));
    mockHttpResponse(301, "Location", "javascript:alert(1)", null);

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("DnsResolver pinning 검증 - 다른 host 요청 시 UnknownHostException (CX-13/CX-20)")
  void dnsResolverPinning() throws Exception {
    InetAddress[] pinned = new InetAddress[] {InetAddress.getByName("8.8.8.8")};
    PinnedDnsResolver resolver = new PinnedDnsResolver(pinned, "news.example.com");

    assertThat(resolver.resolve("news.example.com")).isEqualTo(pinned);
    assertThat(resolver.resolveCanonicalHostname("news.example.com")).isEqualTo("news.example.com");
    assertThatThrownBy(() -> resolver.resolve("evil.com")).isInstanceOf(UnknownHostException.class);
    assertThatThrownBy(() -> resolver.resolveCanonicalHostname("evil.com"))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  @DisplayName("CX-23: connection refused → ExtractionFailedException")
  void connectionRefused() throws Exception {
    mockSsrfApprove(
        "http://news.example.com/article",
        URI.create("http://news.example.com/article"),
        "news.example.com",
        "8.8.8.8");
    Mockito.when(
            mockClient.execute(
                any(ClassicHttpRequest.class),
                any(HttpClientContext.class),
                ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
        .thenThrow(new java.net.ConnectException("Connection refused"));

    assertThatThrownBy(() -> service.extract("http://news.example.com/article"))
        .isInstanceOf(ExtractionFailedException.class);
  }

  /** Helper - mock HTTP response via BasicClassicHttpResponse (CX-26: typed matchers). */
  private void mockHttpResponse(int status, String headerName, String headerValue, byte[] body)
      throws Exception {
    org.apache.hc.core5.http.message.BasicClassicHttpResponse response =
        new org.apache.hc.core5.http.message.BasicClassicHttpResponse(status);
    if (headerName != null) {
      response.addHeader(headerName, headerValue);
    }
    if (body != null) {
      response.setEntity(
          new org.apache.hc.core5.http.io.entity.ByteArrayEntity(
              body, org.apache.hc.core5.http.ContentType.TEXT_HTML));
    }
    Mockito.when(
            mockClient.execute(
                any(ClassicHttpRequest.class),
                any(HttpClientContext.class),
                ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
        .thenAnswer(
            inv -> {
              HttpClientResponseHandler<?> handler = inv.getArgument(2);
              return handler.handleResponse(response);
            });
  }

  private void mockSsrfApprove(String url, URI uri, String host, String ip) throws Exception {
    when(ssrfGuard.validateAndResolve(url))
        .thenReturn(
            new ValidatedTarget(
                uri, host, "http", -1, new InetAddress[] {InetAddress.getByName(ip)}));
  }
}
