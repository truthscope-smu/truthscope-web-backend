package com.truthscope.web.html;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 기사 HTML Document에서 제목/본문/언어/도메인을 추출하는 정적 헬퍼. ArchUnit serviceNaming 룰 회피 위해 service 패키지 외부 분리
 * (R2-6 SsrfGuard 패턴 동일).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArticleHtmlParser {

  public static final int MAX_BODY_LENGTH = 8000;

  private static final String[] BODY_SELECTORS = {
    "#newsct_article", // 네이버 뉴스 (현재)
    "#articeBody", // 네이버 뉴스 (구버전)
    "#harmonyContainer .article_view", // 다음 뉴스
    "article",
    ".article-body",
    ".article_body",
    ".post-content",
    "#content",
    ".content",
    ".entry-content",
    ".news-body",
    ".news_body",
  };

  public static String extractDomain(String url) {
    try {
      return new URI(url).getHost();
    } catch (URISyntaxException e) {
      return "unknown";
    }
  }

  public static String extractTitle(Document doc) {
    Element ogTitle = doc.selectFirst("meta[property=og:title]");
    if (ogTitle != null && !ogTitle.attr("content").isBlank()) {
      return ogTitle.attr("content").trim();
    }
    Element h1 = doc.selectFirst("h1");
    if (h1 != null && !h1.text().isBlank()) {
      return h1.text().trim();
    }
    String title = doc.title();
    if (!title.isBlank()) {
      return title.trim();
    }
    return "";
  }

  public static String extractBody(Document doc) {
    for (String selector : BODY_SELECTORS) {
      Elements elements = doc.select(selector);
      if (!elements.isEmpty()) {
        String text = elements.text().trim();
        if (!text.isBlank()) {
          return truncate(text);
        }
      }
    }
    String bodyText = doc.body() != null ? doc.body().text().trim() : "";
    return truncate(bodyText);
  }

  public static String extractLang(Document doc) {
    Element html = doc.selectFirst("html");
    if (html != null) {
      String lang = html.attr("lang");
      if (!lang.isBlank()) {
        return lang.split("-")[0].trim();
      }
    }
    return "unknown";
  }

  // 발행일 추출용 meta selector (우선순위 순). OpenGraph/article > 표준 date > Dublin Core > schema.org
  // itemprop.
  private static final String[] PUBLISHED_META_SELECTORS = {
    "meta[property=article:published_time]",
    "meta[name=article:published_time]",
    "meta[property=og:article:published_time]",
    "meta[name=date]",
    "meta[name=dc.date]",
    "meta[name=dc.date.issued]",
    "meta[name=dcterms.date]",
    "meta[itemprop=datePublished]",
  };

  // 문자열 내 yyyy-MM-dd / yyyy.MM.dd / yyyy/MM/dd 추출 (ISO 8601 datetime 의 날짜부 포함).
  private static final Pattern ISO_DATE_IN_TEXT =
      Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})");

  // yyyyMMdd 8자리 압축 표기 (네이버 등 일부 언론사 meta).
  private static final Pattern COMPACT_DATE =
      Pattern.compile("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)");

  // JSON-LD 의 datePublished 값 (JSON 파서 없이 정규식으로 추출 — 외부 의존 회피).
  private static final Pattern JSONLD_DATE =
      Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"");

  /**
   * 기사 HTML 에서 발행일을 추출한다. 추출 실패 시 null.
   *
   * <p>우선순위: (1) article:published_time 등 meta 태그 (2) {@code <time datetime>} (3) JSON-LD
   * datePublished. 어느 경로든 yyyy-MM-dd / yyyy.MM.dd / yyyyMMdd 형식의 날짜를 찾으면 LocalDate 로 반환한다.
   *
   * @param doc Jsoup Document
   * @return 발행일 LocalDate, 추출 불가 시 null
   */
  public static LocalDate extractPublishedAt(Document doc) {
    if (doc == null) {
      return null;
    }
    for (String selector : PUBLISHED_META_SELECTORS) {
      Element el = doc.selectFirst(selector);
      if (el != null) {
        LocalDate parsed = parseFlexibleDate(el.attr("content"));
        if (parsed != null) {
          return parsed;
        }
      }
    }
    Element time = doc.selectFirst("time[datetime]");
    if (time != null) {
      LocalDate parsed = parseFlexibleDate(time.attr("datetime"));
      if (parsed != null) {
        return parsed;
      }
    }
    for (Element script : doc.select("script[type=application/ld+json]")) {
      LocalDate parsed = extractJsonLdDate(script.data());
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  /** 임의 문자열에서 첫 번째 yyyy-MM-dd(또는 ./) 또는 yyyyMMdd 날짜를 추출한다. */
  static LocalDate parseFlexibleDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    Matcher m = ISO_DATE_IN_TEXT.matcher(raw);
    if (m.find()) {
      return safeDate(m.group(1), m.group(2), m.group(3));
    }
    Matcher c = COMPACT_DATE.matcher(raw);
    if (c.find()) {
      return safeDate(c.group(1), c.group(2), c.group(3));
    }
    return null;
  }

  /** JSON-LD 문자열에서 datePublished 값을 추출한다. */
  static LocalDate extractJsonLdDate(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    Matcher m = JSONLD_DATE.matcher(json);
    if (m.find()) {
      return parseFlexibleDate(m.group(1));
    }
    return null;
  }

  /** 범위 검증 후 LocalDate 생성. 비정상 값은 null. */
  private static LocalDate safeDate(String yearStr, String monthStr, String dayStr) {
    try {
      int year = Integer.parseInt(yearStr);
      int month = Integer.parseInt(monthStr);
      int day = Integer.parseInt(dayStr);
      if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
        return null;
      }
      return LocalDate.of(year, month, day);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String truncate(String text) {
    if (text.length() <= MAX_BODY_LENGTH) {
      return text;
    }
    int end = MAX_BODY_LENGTH;
    // CodeRabbit PR#40: surrogate pair 경계 보정 (이모지/보조평면 문자가 절단되지 않도록).
    if (Character.isHighSurrogate(text.charAt(end - 1))
        && end < text.length()
        && Character.isLowSurrogate(text.charAt(end))) {
      end--;
    }
    return text.substring(0, end);
  }
}
