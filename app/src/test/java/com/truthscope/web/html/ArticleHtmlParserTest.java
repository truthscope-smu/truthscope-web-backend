package com.truthscope.web.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ArticleHtmlParser 발행일 추출 단위 테스트. */
@DisplayName("ArticleHtmlParser.extractPublishedAt")
class ArticleHtmlParserTest {

  private Document parse(String html) {
    return Jsoup.parse(html);
  }

  @Test
  @DisplayName("article:published_time meta (ISO datetime) 추출")
  void articlePublishedTime_meta() {
    Document doc =
        parse(
            "<html><head>"
                + "<meta property=\"article:published_time\" content=\"2026-05-08T09:30:00+09:00\">"
                + "</head><body>본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2026, 5, 8));
  }

  @Test
  @DisplayName("name=date meta 추출")
  void nameDate_meta() {
    Document doc =
        parse(
            "<html><head><meta name=\"date\" content=\"2025.12.01\"></head><body>본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2025, 12, 1));
  }

  @Test
  @DisplayName("JSON-LD datePublished 추출 (meta 부재 시)")
  void jsonLd_datePublished() {
    Document doc =
        parse(
            "<html><head>"
                + "<script type=\"application/ld+json\">"
                + "{\"@type\":\"NewsArticle\",\"datePublished\":\"2024-06-15T00:00:00Z\"}"
                + "</script></head><body>본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2024, 6, 15));
  }

  @Test
  @DisplayName("time[datetime] 추출 (meta·JSON-LD 부재 시)")
  void timeDatetime() {
    Document doc =
        parse("<html><body><time datetime=\"2025-03-10\">3월 10일</time> 본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2025, 3, 10));
  }

  @Test
  @DisplayName("yyyyMMdd 압축 표기 meta 추출")
  void compactDate_meta() {
    Document doc =
        parse("<html><head><meta name=\"date\" content=\"20260508\"></head><body>본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2026, 5, 8));
  }

  @Test
  @DisplayName("meta 우선순위: article:published_time 가 JSON-LD 보다 우선")
  void metaPriority_overJsonLd() {
    Document doc =
        parse(
            "<html><head>"
                + "<meta property=\"article:published_time\" content=\"2026-05-08\">"
                + "<script type=\"application/ld+json\">{\"datePublished\":\"2020-01-01\"}</script>"
                + "</head><body>본문</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isEqualTo(LocalDate.of(2026, 5, 8));
  }

  @Test
  @DisplayName("날짜 정보 없으면 null")
  void noDate_returnsNull() {
    Document doc = parse("<html><head><title>제목</title></head><body>본문만 있음</body></html>");

    assertThat(ArticleHtmlParser.extractPublishedAt(doc)).isNull();
  }

  @Test
  @DisplayName("null Document 는 null")
  void nullDoc_returnsNull() {
    assertThat(ArticleHtmlParser.extractPublishedAt(null)).isNull();
  }

  @Test
  @DisplayName("parseFlexibleDate: 범위 밖 날짜는 null")
  void parseFlexibleDate_outOfRange() {
    assertThat(ArticleHtmlParser.parseFlexibleDate("1999-13-40")).isNull();
    assertThat(ArticleHtmlParser.parseFlexibleDate("no date here")).isNull();
    assertThat(ArticleHtmlParser.parseFlexibleDate("")).isNull();
    assertThat(ArticleHtmlParser.parseFlexibleDate(null)).isNull();
  }
}
