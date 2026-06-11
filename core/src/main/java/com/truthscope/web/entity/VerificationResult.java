package com.truthscope.web.entity;

import com.truthscope.web.entity.enums.SupersedeReason;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.entity.enums.Verdict;
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

@Entity
@Table(name = "verification_results")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class VerificationResult extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "claim_id")
  private Claim claim;

  @Column(name = "tier")
  private Short tier;

  @Enumerated(EnumType.STRING)
  @Column(name = "verdict", length = 30, nullable = false)
  private Verdict verdict;

  /**
   * @deprecated Tier 파생 표시값(legacy). 무결성 기준은 V3부터 verdict로 이전됐다. V3에서 nullable 전환, 물리 DROP은 V4 예정.
   *     신규 의미 부여 금지(ADR-014 Accepted).
   */
  @Deprecated
  @Column(name = "label", length = 30)
  private String label;

  @Column(name = "score")
  private Short score;

  @Column(name = "reason", columnDefinition = "TEXT")
  private String reason;

  @Column(name = "disclaimer", columnDefinition = "TEXT")
  private String disclaimer;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "tier3_reason", length = 30)
  private Tier3Reason tier3Reason;

  // ── Supersede 체인 컬럼 (ADR-009 rev.3 · V9 마이그레이션 정합) ──────────────

  @Column(name = "superseded_at")
  private LocalDateTime supersededAt;

  @Column(name = "superseded_by_result_id", columnDefinition = "uuid")
  private UUID supersededByResultId;

  @Enumerated(EnumType.STRING)
  @Column(name = "supersede_reason", length = 30)
  private SupersedeReason supersedeReason;

  @Column(name = "original_result_id", columnDefinition = "uuid")
  private UUID originalResultId;

  @Column(name = "last_confirmed_at")
  private LocalDateTime lastConfirmedAt;

  // ── 비즈니스 메서드 (@Setter 금지 — 변경은 비즈니스 메서드로만) ────────────────

  /**
   * 이 결과를 supersede 처리한다.
   *
   * <p>영속 순서 제약: 반드시 flush 후 새 결과를 INSERT하고 그 다음 linkSupersededBy 호출. 마킹-flush-INSERT-link 순서를 어기면
   * partial unique(uq_vr_claim_current) 위반이 발생한다.
   *
   * @param reason 정정 사유 (ADR-009 4조건 OR)
   */
  public void markSuperseded(SupersedeReason reason) {
    this.supersededAt = LocalDateTime.now();
    this.supersedeReason = reason;
  }

  /**
   * 이 결과를 대체한 새 결과의 ID를 연결한다.
   *
   * @param byResultId 대체 결과 ID
   */
  public void linkSupersededBy(UUID byResultId) {
    this.supersededByResultId = byResultId;
  }

  /**
   * 재검증 수행 결과가 변경 없음으로 확인된 시각을 기록한다.
   *
   * <p>4조건 모두 미충족 시 호출. supersede 없이 확인 증거만 남긴다.
   */
  public void confirmRecheck() {
    this.lastConfirmedAt = LocalDateTime.now();
  }

  /**
   * 현재 유효한 결과인지 여부를 반환한다.
   *
   * @return superseded_at 이 NULL이면 현재 유효(true), 아니면 이미 정정된 결과(false)
   */
  public boolean isCurrent() {
    return this.supersededAt == null;
  }
}
