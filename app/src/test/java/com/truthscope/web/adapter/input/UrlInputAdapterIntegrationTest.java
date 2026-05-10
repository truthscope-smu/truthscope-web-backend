package com.truthscope.web.adapter.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.ArticleSource;
import com.truthscope.web.exception.BadRequestException;
import com.truthscope.web.exception.ExtractionFailedException;
import com.truthscope.web.exception.SsrfBlockedException;
import com.truthscope.web.security.SsrfGuard;
import com.truthscope.web.service.ContentExtractService;
import com.truthscope.web.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * UrlInputAdapter 통합 테스트 — RED 단계.
 *
 * <p>UrlInputAdapter는 아직 미구현 상태이므로 컴파일 오류가 발생하여 RED를 보장한다. AbstractIntegrationTest를 상속하여
 * Testcontainers Singleton PostgreSQL + Spring Context를 사용한다.
 *
 * <p>외부 의존성 처리 전략:
 *
 * <ul>
 *   <li>Scenario 1, 4: {@code @MockBean ContentExtractService} — 실제 네트워크 호출 없이 추출 결과/예외를 제어
 *   <li>Scenario 2, 3, 5: SsrfGuard 실제 호출 — URL 형식/사설IP 검증은 네트워크 없이 JVM 단위에서 수행됨
 * </ul>
 *
 * <p>Wave 2 Codex 평가 항목: {@code @MockBean ContentExtractService} vs Classicist(실제 HTTP서버) 트레이드오프.
 */
@DisplayName("UrlInputAdapter 통합 테스트")
class UrlInputAdapterIntegrationTest extends AbstractIntegrationTest {

  // RED 핵심: UrlInputAdapter는 아직 미구현 → 컴파일 오류 발생 (cannot find symbol)
  @Autowired private UrlInputAdapter urlInputAdapter;

  // Scenario 1 (happy path) + Scenario 4 (timeout/5xx): ContentExtractService Mock
  // 실제 외부 네트워크 없이 추출 성공/실패 시나리오를 제어한다
  @MockBean private ContentExtractService contentExtractService;

  // ─────────────────────────────────────────────────
  // Scenario 1: 정상 HTTPS URL → Article 도메인 객체 반환
  // ─────────────────────────────────────────────────

  @Test
  @DisplayName("정상_HTTPS_URL이면_Article을_반환한다")
  void 정상_HTTPS_URL이면_Article을_반환한다() {
    // Given: 외부 IP literal(1.1.1.1)을 사용해 DNS 의존 0 + SsrfGuard 통과 (외부 대역).
    // gh runner / 오프라인 환경에서도 안정적 (가짜 호스트명 DNS 실패 회피).
    String url = "https://1.1.1.1/article/12345";
    ExtractedArticle extracted =
        ExtractedArticle.builder()
            .title("팩트체크: AI 관련 보도")
            .body("기사 본문 내용입니다.")
            .lang("ko")
            .domain("1.1.1.1")
            .build();
    // D-4 LOCK 정합: ValidatedTarget overload mock — adapter는 SsrfGuard 검증 결과를 그대로 service에 전달
    when(contentExtractService.extract(any(SsrfGuard.ValidatedTarget.class))).thenReturn(extracted);

    // When
    Article result = urlInputAdapter.extractFromUrl(url);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.getUrl()).isEqualTo(url);
    assertThat(result.getTitle()).isEqualTo("팩트체크: AI 관련 보도");
    assertThat(result.getBody()).isEqualTo("기사 본문 내용입니다.");
    assertThat(result.getLang()).isEqualTo("ko");
    assertThat(result.getDomain()).isEqualTo("1.1.1.1");
    // invariant 검증: url은 https://로 시작해야 한다
    assertThat(result.getUrl()).startsWith("https://");
    // invariant 검증: extractedAt은 null이 아니어야 한다
    assertThat(result.getExtractedAt()).isNotNull();
    // source-aware 검증: URL_INPUT 입구로 들어왔음이 박제되어야 한다 (D-2 ArticleSource B-light)
    assertThat(result.getSource()).isEqualTo(ArticleSource.URL_INPUT);
  }

  // ─────────────────────────────────────────────────
  // Scenario 2: 비허용 스킴(ftp://) → BadRequestException + 외부 fetch 차단
  // 가드 우선 검증 (D-3 guard-before-fetch): 어댑터가 SsrfGuard를 직접 호출하므로
  // 외부 fetch는 절대 일어나지 않아야 한다 → verifyNoInteractions로 박제
  // ─────────────────────────────────────────────────

  @Test
  @DisplayName("잘못된_스킴_URL이면_가드에서_거부되고_외부_fetch는_시도되지_않는다")
  void 잘못된_스킴_URL이면_가드에서_거부되고_외부_fetch는_시도되지_않는다() {
    // Given
    String ftpUrl = "ftp://example.com/file.txt";

    // When / Then
    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(ftpUrl))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("유효하지 않은 URL 형식입니다");
    // guard-before-fetch 박제: 어댑터는 SsrfGuard 검증 통과 전에는 ContentExtractService 호출 금지
    verifyNoInteractions(contentExtractService);
  }

  // ─────────────────────────────────────────────────
  // Scenario 3: 사설 IP URL → SsrfBlockedException + 외부 fetch 차단
  // ─────────────────────────────────────────────────

  @Test
  @DisplayName("사설_IP_URL이면_가드에서_차단되고_외부_fetch는_시도되지_않는다")
  void 사설_IP_URL이면_가드에서_차단되고_외부_fetch는_시도되지_않는다() {
    // Given: 192.168.1.1은 RFC1918 사설 IP
    String privateIpUrl = "http://192.168.1.1/admin";

    // When / Then
    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(privateIpUrl))
        .isInstanceOf(SsrfBlockedException.class)
        .hasMessageContaining("내부 네트워크 주소는 차단되었습니다");
    verifyNoInteractions(contentExtractService);
  }

  // ─────────────────────────────────────────────────
  // Scenario 3-edge: SSRF 추가 IP 4건 (codex M3 권고)
  // localhost 호스트명 / IPv4 loopback / 클라우드 메타데이터 / IPv6 loopback
  // 각 케이스 모두 가드에서 차단되어야 하며 외부 fetch 시도 0회여야 한다
  // ─────────────────────────────────────────────────

  @ParameterizedTest(name = "차단_대상 = [{0}]")
  @DisplayName("내부망_대역_URL은_모두_가드에서_차단된다")
  @ValueSource(
      strings = {
        "http://localhost/path", // localhost 호스트명
        "http://127.0.0.1/admin", // IPv4 loopback
        "http://169.254.169.254/meta", // 클라우드 메타데이터 (AWS/GCP)
        "http://[::1]/path" // IPv6 loopback
      })
  void 내부망_대역_URL은_모두_가드에서_차단된다(String blockedUrl) {
    // Given: 모두 SsrfGuard deny CIDR 또는 loopback 대역
    // When / Then
    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(blockedUrl))
        .isInstanceOf(SsrfBlockedException.class);
    verifyNoInteractions(contentExtractService);
  }

  // ─────────────────────────────────────────────────
  // Scenario 4: 외부 뉴스 서버 응답 실패 → ExtractionFailedException 전파
  // (이름 변경 — 구현 기술 'Jsoup' 노출 제거, 도메인 의도 'fetch 실패'로 명시)
  // ─────────────────────────────────────────────────

  @Test
  @DisplayName("외부_뉴스_서버_응답_실패면_기사를_가져올_수_없다는_신호를_전파한다")
  void 외부_뉴스_서버_응답_실패면_기사를_가져올_수_없다는_신호를_전파한다() {
    // Given: 외부 IP(8.8.8.8) — SsrfGuard 통과 + Mock ContentExtractService가 fetch 실패 simulation
    // D-4 LOCK 정합: ValidatedTarget overload mock
    String url = "https://8.8.8.8/article/99";
    when(contentExtractService.extract(any(SsrfGuard.ValidatedTarget.class)))
        .thenThrow(new ExtractionFailedException("기사를 가져올 수 없습니다: " + url));

    // When / Then
    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(url))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("기사를 가져올 수 없습니다");
  }

  // ─────────────────────────────────────────────────
  // Scenario 5: null 또는 blank URL → BadRequestException + 외부 fetch 차단
  // ─────────────────────────────────────────────────

  @ParameterizedTest(name = "입력값 = [{0}]")
  @DisplayName("null_또는_blank_URL이면_가드에서_거부되고_외부_fetch는_시도되지_않는다")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "   ", "\t", "\n"})
  void null_또는_blank_URL이면_가드에서_거부되고_외부_fetch는_시도되지_않는다(String blankUrl) {
    // Given: null, 빈 문자열, 공백 문자열 모두 SsrfGuard에서 BadRequestException
    // When / Then
    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(blankUrl))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(contentExtractService);
  }
}
