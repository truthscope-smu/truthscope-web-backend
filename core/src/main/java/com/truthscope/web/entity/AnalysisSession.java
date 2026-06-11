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

  /**
   * 재검증 후 집계 필드만 갱신한다 — 최초 완료 시각 보존, ADR-009 이력 원칙.
   *
   * <p>status 와 completedAt 은 변경하지 않는다. ArticleFactScoreAggregator + CoverageAggregator 재실행 결과를 기존
   * 세션에 덮어쓸 때 사용한다.
   *
   * @param totalScore Phase 55 ArticleFactScoreAggregator 결과 Short(0..100), 검증 가능 claim 없으면 null
   * @param coverage CoverageAggregator 집계 결과 (non-null)
   * @param tier1Count Tier 1 signal 수
   * @param tier2Count Tier 2 signal 수
   * @param tier3Count Tier 3 signal 수
   */
  public void updateAggregates(
      Short totalScore,
      CoverageSummary coverage,
      Short tier1Count,
      Short tier2Count,
      Short tier3Count) {
    this.totalScore = totalScore;
    this.coverage = coverage;
    this.tier1Count = tier1Count;
    this.tier2Count = tier2Count;
    this.tier3Count = tier3Count;
    // status 와 completedAt 은 의도적으로 변경하지 않는다.
  }

  /**
   * Wave 2 cascade 영속화 완료 후 세션 집계 필드를 갱신하고 상태를 COMPLETED로 전이한다.
   *
   * <p>@Setter 없음 원칙 준수 — AnalysisTransactionService.persistCascadeResults 가 호출. totalScore /
   * coverage / tier1~3Count 는 Phase 55 집계 4함수 결과값이다. totalScore=null 이면 검증 가능 claim 0건(기사 전체 Tier 3
   * 판정).
   *
   * @param totalScore Phase 55 ArticleFactScoreAggregator 결과 Short(0..100), 검증 가능 claim 없으면 null
   * @param coverage CoverageAggregator 집계 결과 (non-null, 빈 경우 모든 count=0)
   * @param tier1Count Tier 1 signal 수
   * @param tier2Count Tier 2 signal 수
   * @param tier3Count Tier 3 signal 수
   */
  public void completeCascade(
      Short totalScore,
      CoverageSummary coverage,
      Short tier1Count,
      Short tier2Count,
      Short tier3Count) {
    this.totalScore = totalScore;
    this.coverage = coverage;
    this.tier1Count = tier1Count;
    this.tier2Count = tier2Count;
    this.tier3Count = tier3Count;
    this.status = SessionStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
  }
}
