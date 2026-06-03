package com.truthscope.web.dto.response;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 뉴스 기사 URL에서 추출된 콘텐츠 응답 DTO */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedArticle {

  /** 기사 제목 */
  private String title;

  /** 기사 본문 (최대 8000자) */
  private String body;

  /** 언어 코드 (예: "ko", "en", "unknown") */
  private String lang;

  /** 도메인 (예: "news.naver.com") */
  private String domain;

  /**
   * 기사 발행일 (nullable). meta 태그(article:published_time / JSON-LD datePublished / Dublin Core)에서 추출.
   * 추출 실패 시 null. Tier 2 evidence 윈도우의 기준일 폴백으로 사용된다 (claimText 날짜 우선, 없으면 이 발행일, 그것도 없으면 today).
   */
  private LocalDate publishedAt;
}
