package com.truthscope.web.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "articles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Article extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", unique = true)
  private AnalysisSession session;

  @Column(name = "url", length = 2048)
  private String url;

  @Column(name = "title", length = 500)
  private String title;

  @Column(name = "body", columnDefinition = "TEXT")
  private String body;

  @Column(name = "lang", length = 10)
  private String lang;

  @Column(name = "domain", length = 255)
  private String domain;

  @Column(name = "extracted_at")
  private LocalDateTime extractedAt;

  /**
   * 기사가 어느 입구로 들어왔는지 박제한다 (DDD source-aware aggregate 패턴).
   *
   * <p>{@link ArticleSource#URL_INPUT}이면 url 필수 + http(s) 스킴, {@link ArticleSource#TEXT_INPUT}이면
   * url null 허용. 두 invariant가 source별로 다르므로 본 필드가 polymorphic 검증의 근거가 된다.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 20)
  private ArticleSource source;

  /**
   * 외부 뉴스 URL에서 fetch하여 추출된 기사를 invariant 만족 상태로만 생성한다 (DDD always-valid 모델).
   *
   * <p>url은 http(s) 스킴이어야 하며 null/blank 허용 안 함. 다른 필드는 추출 단계에 따라 부재할 수 있어 nullable. 세션은 별도 {@link
   * #attachTo(AnalysisSession)} 호출로 부착한다.
   *
   * <p>본 팩토리로 만들어진 기사는 {@code source = URL_INPUT}으로 박제된다.
   */
  public static Article extract(String url, String title, String body, String lang, String domain) {
    validateUrl(url);
    return Article.builder()
        .url(url)
        .title(title)
        .body(body)
        .lang(lang)
        .domain(domain)
        .source(ArticleSource.URL_INPUT)
        .extractedAt(LocalDateTime.now())
        .build();
  }

  /**
   * 사용자가 직접 붙여넣은 raw text에서 기사를 invariant 만족 상태로 생성한다.
   *
   * <p>url은 외부 출처가 없으므로 null. title은 null/blank 금지 (의미 있는 기사의 최소 식별자). body는 빈 문자열 허용 (단일 줄 입력 정책).
   * lang은 nullable (호출자가 MVP 단계에서 "ko" 고정).
   *
   * <p>본 팩토리로 만들어진 기사는 {@code source = TEXT_INPUT}으로 박제되며 {@code domain = "user-input"} 고정.
   */
  public static Article fromText(String title, String body, String lang) {
    validateText(title);
    return Article.builder()
        .url(null)
        .title(title)
        .body(body == null ? "" : body)
        .lang(lang)
        .domain("user-input")
        .source(ArticleSource.TEXT_INPUT)
        .extractedAt(LocalDateTime.now())
        .build();
  }

  /**
   * 분석 세션에 1회만 부착한다 (invariant). 이미 부착된 Article을 다시 부착하면 {@link IllegalStateException}.
   *
   * @return this — 메서드 체이닝용
   */
  public Article attachTo(AnalysisSession session) {
    if (session == null) {
      throw new IllegalArgumentException("session은 null일 수 없습니다");
    }
    if (this.session != null) {
      throw new IllegalStateException("Article은 이미 세션에 부착되었습니다");
    }
    this.session = session;
    return this;
  }

  private static void validateUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("url은 null이거나 비어 있을 수 없습니다");
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw new IllegalArgumentException("url은 http:// 또는 https://로 시작해야 합니다");
    }
  }

  private static void validateText(String title) {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title은 null이거나 비어 있을 수 없습니다");
    }
  }
}
