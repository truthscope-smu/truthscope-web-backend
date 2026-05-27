package com.truthscope.web.adapter.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WikipediaRevisionChecker 단위")
class WikipediaRevisionCheckerTest {

  @Test
  @DisplayName("VANDALISM_THRESHOLD는 5이다")
  void threshold_is5() {
    assertThat(WikipediaRevisionChecker.VANDALISM_THRESHOLD).isEqualTo(5);
  }

  @Test
  @DisplayName("null title → UNKNOWN 반환")
  void nullTitle_returnsUnknown() {
    WikipediaRevisionChecker checker =
        new WikipediaRevisionChecker(org.springframework.web.client.RestClient.builder());
    assertThat(checker.check(null, "ko")).isEqualTo(VandalismStatus.UNKNOWN);
  }

  @Test
  @DisplayName("blank title → UNKNOWN 반환")
  void blankTitle_returnsUnknown() {
    WikipediaRevisionChecker checker =
        new WikipediaRevisionChecker(org.springframework.web.client.RestClient.builder());
    assertThat(checker.check("  ", "ko")).isEqualTo(VandalismStatus.UNKNOWN);
  }

  // H2 amend: fail-closed 가드 단위 테스트 — malformed JSON에서 UNKNOWN 반환 검증 (변종 E 대응)
  @Test
  @DisplayName("malformed JSON → countRecentRevisions는 -1 반환 → UNKNOWN (fail-closed 가드)")
  void malformedJson_returnsUnknown() {
    WikipediaRevisionChecker checker =
        new WikipediaRevisionChecker(org.springframework.web.client.RestClient.builder());
    java.time.Clock fixedClock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-05-27T11:00:00Z"), java.time.ZoneOffset.UTC);
    int result = checker.countRecentRevisions("NOT_VALID_JSON{{{", fixedClock);
    assertThat(result).isEqualTo(-1);
  }

  @Test
  @DisplayName("empty body → countRecentRevisions는 -1 반환 → UNKNOWN (fail-closed 가드)")
  void emptyBody_returnsMinusOne() {
    WikipediaRevisionChecker checker =
        new WikipediaRevisionChecker(org.springframework.web.client.RestClient.builder());
    java.time.Clock fixedClock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-05-27T11:00:00Z"), java.time.ZoneOffset.UTC);
    int result = checker.countRecentRevisions("", fixedClock);
    assertThat(result).isEqualTo(-1);
  }

  // H2 codex Round 2 미해소 amend: countRecentRevisions() == -1 시 check() 전체가 UNKNOWN을 반환하는지 검증.
  // 기존 테스트는 countRecentRevisions(-1) 반환만 검사했고 check() == UNKNOWN은 검사하지 않아
  // 변종 E 무력화(fail-closed 가드 제거) 시 이 테스트가 실제 fail하도록 end-to-end 검증 추가.
  //
  // H2 codex Round 3 amend: WireMock stub으로 malformed JSON을 직접 주입하고 checker.check(...)를
  // public API로 직접 호출하여 end-to-end UNKNOWN 반환을 검증한다.
  // WireMockServer를 직접 생성하여 단위 테스트 수준에서 실 HTTP 응답 stub 주입.
  @Test
  @DisplayName(
      "check_malformed_JSON_UNKNOWN_반환 — WireMock stub malformed JSON + checker.check() public API end-to-end 검증 (H2 codex Round 3 amend)")
  void check_malformed_JSON_UNKNOWN_반환() throws Exception {
    // given: WireMock 서버 직접 생성 + malformed JSON stub 등록
    com.github.tomakehurst.wiremock.WireMockServer wireMock =
        new com.github.tomakehurst.wiremock.WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    try {
      wireMock.stubFor(
          com.github.tomakehurst.wiremock.client.WireMock.get(
                  com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/w/api.php.*"))
              .willReturn(
                  com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{ invalid json no closing brace")));

      // WireMock URL을 baseUrl로 주입하는 WikipediaRevisionChecker 생성
      // @Value 주입 우회: 생성자에 restClientBuilder를 custom baseUrl로 전달
      // RestClient.Builder는 baseUrl을 @Value가 아닌 WikipediaRevisionChecker 내부 @Value로 설정하므로
      // 테스트용 properties로 오버라이드 또는 직접 URL 치환 패턴 사용
      String wireMockBaseUrl = "http://localhost:" + wireMock.port();
      // WikipediaRevisionChecker는 @Value로 koBaseUrl/enBaseUrl 주입 — 단위 테스트에서는
      // ReflectionTestUtils로 baseUrl 필드를 직접 주입하거나, 테스트용 생성자/setter 활용
      WikipediaRevisionChecker checker =
          new WikipediaRevisionChecker(org.springframework.web.client.RestClient.builder());
      // ReflectionTestUtils로 koBaseUrl 필드를 WireMock URL로 override
      org.springframework.test.util.ReflectionTestUtils.setField(
          checker, "koBaseUrl", wireMockBaseUrl);
      org.springframework.test.util.ReflectionTestUtils.setField(
          checker, "enBaseUrl", wireMockBaseUrl);

      // when: checker.check() public API 직접 호출 (countRecentRevisions가 아닌 check() 전체 경로)
      VandalismStatus status = checker.check("삼성전자", "ko");

      // then: WireMock이 malformed JSON 응답 → countRecentRevisions = -1 → fail-closed 가드 → UNKNOWN
      assertThat(status).isEqualTo(VandalismStatus.UNKNOWN);
      // 변종 E 무력화 시: recentCount=-1 <= VANDALISM_THRESHOLD=5 → STABLE 오분류 → 이 assert fail
    } finally {
      wireMock.stop();
    }
  }
}
