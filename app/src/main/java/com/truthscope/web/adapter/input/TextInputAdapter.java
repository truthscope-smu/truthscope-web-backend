package com.truthscope.web.adapter.input;

import com.truthscope.web.entity.Article;
import org.springframework.stereotype.Component;

/**
 * 사용자가 직접 붙여넣은 raw text를 분석 가능한 {@link Article} aggregate로 변환하는 input port (Hexagonal ACL).
 *
 * <p>외부 네트워크 접근이 없으므로 SsrfGuard 호출 불필요. 의존성 0 — Spring Context 없이도 단위 테스트 가능 ({@code new
 * TextInputAdapter()}).
 *
 * <p>본 어댑터로 만들어진 {@link Article}은 {@link com.truthscope.web.entity.ArticleSource#TEXT_INPUT}으로 박제되며
 * {@code url = null}, {@code domain = "user-input"}이다.
 *
 * <p>분리 정책 (PR #24-cleanup 확정):
 *
 * <ul>
 *   <li>입력은 첫 줄을 제목, 나머지를 본문으로 분리한다
 *   <li>단일 줄 입력은 valid (body는 빈 문자열)
 *   <li>lang은 MVP에서 "ko" 고정 (자동 감지는 후속 issue)
 *   <li>title 길이 cap / sanitization은 미적용 (DB 시점 처리, 후속 issue)
 * </ul>
 *
 * <p>BDD 시나리오: {@code docs/md/BDD-text-input-adapter.md}.
 */
@Component
public class TextInputAdapter {

  private static final String DEFAULT_LANG = "ko";

  /**
   * raw text를 받아 기사 카드 1장으로 변환한다.
   *
   * <p>처리 단계:
   *
   * <ol>
   *   <li>null/blank 검증 (실패 시 즉시 IllegalArgumentException)
   *   <li>앞뒤 공백 제거
   *   <li>첫 줄 → 제목, 나머지 → 본문 (줄바꿈 없으면 본문은 빈 문자열)
   *   <li>{@link Article#fromText(String, String, String)}로 TEXT_INPUT 출처 aggregate 생성
   * </ol>
   *
   * @throws IllegalArgumentException text가 null이거나 공백만 포함된 경우
   */
  public Article extractFromText(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      throw new IllegalArgumentException("text는 null이거나 비어 있을 수 없습니다");
    }

    String trimmed = rawText.trim();
    int firstNewline = trimmed.indexOf('\n');
    String title;
    String body;
    if (firstNewline == -1) {
      title = trimmed;
      body = "";
    } else {
      title = trimmed.substring(0, firstNewline).trim();
      body = trimmed.substring(firstNewline + 1).trim();
    }

    return Article.fromText(title, body, DEFAULT_LANG);
  }
}
