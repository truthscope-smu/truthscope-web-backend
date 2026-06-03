package com.truthscope.web.converter;

import com.truthscope.web.dto.projection.AnalysisSessionHistoryRow;
import com.truthscope.web.dto.response.AnalysisSessionHistoryResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 이력 조회 Row projection → 응답 DTO 변환기. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AnalysisSessionHistoryConverter {

  public static AnalysisSessionHistoryResponse toResponse(AnalysisSessionHistoryRow row) {
    return AnalysisSessionHistoryResponse.builder()
        .sessionId(row.sessionId())
        .articleId(row.articleId())
        .articleTitle(row.articleTitle())
        .articleUrl(row.articleUrl())
        .articleDomain(row.articleDomain())
        .status(row.status() == null ? null : row.status().name())
        .totalScore(row.totalScore())
        .requestedAt(row.requestedAt())
        .completedAt(row.completedAt())
        .build();
  }
}
