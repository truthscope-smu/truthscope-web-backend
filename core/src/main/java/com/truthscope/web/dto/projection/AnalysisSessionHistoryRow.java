package com.truthscope.web.dto.projection;

import com.truthscope.web.entity.enums.SessionStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/** JPQL projection record — findHistoryByMemberId 결과 매핑. */
public record AnalysisSessionHistoryRow(
    UUID sessionId,
    UUID articleId,
    String articleTitle,
    String articleUrl,
    String articleDomain,
    SessionStatus status,
    Short totalScore,
    LocalDateTime requestedAt,
    LocalDateTime completedAt) {}
