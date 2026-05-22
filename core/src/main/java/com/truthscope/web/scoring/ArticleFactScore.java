package com.truthscope.web.scoring;

/**
 * 기사 종합 사실 검증 점수(DISCUSS D13). value는 0..100 정수. 검증 가능 claim이 0개인 기사는 이 객체를 만들지
 * 않는다(aggregateArticleFactScore가 Optional.empty 반환).
 */
public record ArticleFactScore(int value) {
  public ArticleFactScore {
    if (value < 0 || value > 100) {
      throw new IllegalArgumentException("기사 점수는 0..100이어야 한다: " + value);
    }
  }
}
