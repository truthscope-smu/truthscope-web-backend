package com.truthscope.web.entity;

import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.scoring.CoverageSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_sessions")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AnalysisSession extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "member_id")
  private Member member;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20)
  private SessionStatus status;

  @Column(name = "requested_at")
  private LocalDateTime requestedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "total_score")
  private Short totalScore;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "coverage", columnDefinition = "jsonb")
  private CoverageSummary coverage; // Phase 55 D14 정합, JSON 직렬화 (이전 String VARCHAR(10) → JSONB)

  @Column(name = "tier1_count")
  private Short tier1Count;

  @Column(name = "tier2_count")
  private Short tier2Count;

  @Column(name = "tier3_count")
  private Short tier3Count;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** 세션 상태 변경 (비즈니스 메서드 — @Setter 대체) */
  public void updateStatus(SessionStatus newStatus) {
    this.status = newStatus;
  }
}
