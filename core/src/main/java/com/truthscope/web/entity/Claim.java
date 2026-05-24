package com.truthscope.web.entity;

import com.truthscope.web.entity.enums.ClaimImportance;
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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "claims")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Claim extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "article_id")
  private Article article;

  @Column(name = "text", columnDefinition = "TEXT")
  private String text;

  @Enumerated(EnumType.STRING)
  @Column(name = "importance", length = 10)
  private ClaimImportance importance;

  @Column(name = "sort_order")
  private Short sortOrder;

  @Column(name = "speaker_name", length = 255)
  private String speakerName;

  @Column(name = "is_quoted_claim", nullable = false)
  @Builder.Default
  private boolean isQuotedClaim = false;

  @Column(name = "original_context", columnDefinition = "TEXT")
  private String originalContext;

  /** Attribution 메타데이터 부착 (BE #76 + ADR-020 SIFT T). ClaimAttributionService 가 호출. */
  public void attachSpeaker(String speakerName, boolean isQuotedClaim, String originalContext) {
    this.speakerName = speakerName;
    this.isQuotedClaim = isQuotedClaim;
    this.originalContext = originalContext;
  }
}
