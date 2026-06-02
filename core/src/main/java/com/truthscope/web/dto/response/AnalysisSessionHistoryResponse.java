package com.truthscope.web.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인증 사용자 분석 이력 단건 응답 DTO. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisSessionHistoryResponse {

  private UUID sessionId;

  /** null = 아직 기사 추출 전 (PENDING) */
  private UUID articleId;

  private String articleTitle;
  private String articleUrl;
  private String articleDomain;

  /** SessionStatus.name() 문자열 */
  private String status;

  /** null = 검증 가능 주장 없음 */
  private Short totalScore;

  private LocalDateTime requestedAt;

  /** null = 미완료 */
  private LocalDateTime completedAt;
}
