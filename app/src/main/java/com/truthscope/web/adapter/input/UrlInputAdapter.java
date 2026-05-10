package com.truthscope.web.adapter.input;

import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.Article;
import com.truthscope.web.security.SsrfGuard;
import com.truthscope.web.service.ContentExtractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 입력한 뉴스 URL을 분석 가능한 {@link Article} aggregate로 변환하는 input port (Hexagonal ACL).
 *
 * <p>guard-before-fetch 패턴 — {@link SsrfGuard}를 직접 호출해 사설 IP/내부망을 차단한 뒤에만 외부 fetch를 시도한다. {@link
 * ContentExtractService} 내부에서도 가드를 호출하지만, 어댑터에서 한 번 더 호출함으로써 통합 테스트가 {@code @MockBean
 * ContentExtractService} 환경에서도 가드 경로를 검증할 수 있다 (defense-in-depth).
 *
 * <p>본 어댑터로 만들어진 {@link Article}은 {@link com.truthscope.web.entity.ArticleSource#URL_INPUT}으로 박제된다.
 *
 * <p>BDD 시나리오: {@code docs/md/BDD-url-input-adapter.md}.
 */
@Component
@RequiredArgsConstructor
public class UrlInputAdapter {

  private final SsrfGuard ssrfGuard;
  private final ContentExtractService contentExtractService;

  /**
   * URL을 받아 기사 카드 1장으로 변환한다.
   *
   * <ol>
   *   <li>{@link SsrfGuard}로 URL 형식 + 사설 IP 검증 (fetch 전)
   *   <li>{@link ContentExtractService}로 본문 추출
   *   <li>{@link Article#extract(String, String, String, String, String)}로 invariant 만족 aggregate
   *       생성
   * </ol>
   *
   * @throws com.truthscope.web.exception.BadRequestException URL null/blank/형식 오류
   * @throws com.truthscope.web.exception.SsrfBlockedException 사설 IP/내부망 대역
   * @throws com.truthscope.web.exception.ExtractionFailedException 외부 fetch 실패
   *     (timeout/5xx/parsing)
   */
  public Article extractFromUrl(String url) {
    // 1. 가드 우선 — fetch 시도 전에 사설망/형식 오류 차단 + 검증 결과(ValidatedTarget) 보존
    SsrfGuard.ValidatedTarget target = ssrfGuard.validateAndResolve(url);

    // 2. 가드 통과 후 검증된 target을 그대로 전달 — D-4 LOCK (Codex thread 019e1096): SsrfGuard 중복 호출 제거.
    //    boundary는 본 adapter → ContentExtractService에 제한 (controller/DTO까지 ValidatedTarget 노출 금지).
    ExtractedArticle extracted = contentExtractService.extract(target);

    // 3. URL_INPUT 출처로 Article 생성 (invariant: url 필수, http(s) 시작)
    return Article.extract(
        url, extracted.getTitle(), extracted.getBody(), extracted.getLang(), extracted.getDomain());
  }
}
