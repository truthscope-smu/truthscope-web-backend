package com.truthscope.web.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.truthscope.web.scoring.CoverageSummary;
import com.truthscope.web.scoring.SourceTransparencySummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기사 검증 결과 조회 응답 DTO (UC-140 결과 카드 표시, EO). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleVerificationResponse {

  /** Article ID */
  private UUID articleId;

  /** 기사 URL */
  private String url;

  /** 제목 */
  private String title;

  /** SessionStatus.name() */
  private String status;

  /** 기사 단위 검증완료 시각 (session.completedAt) */
  private LocalDateTime analysisCompletedAt;

  /** null = 검증가능 claim 0건 */
  private Short totalScore;

  /** TruthLabel.name() 도출값, null 가능 */
  private String articleLabel;

  /** JSONB record 그대로 (Jackson 직렬화) */
  private CoverageSummary coverage;

  /** 단일 출처: AnalysisSession 최상위 Short (C-4) */
  private Short tier1Count;

  private Short tier2Count;

  private Short tier3Count;

  /** 66a=null, 66b에서 채움 */
  private SourceTransparencySummary sourceTransparency;

  @Builder.Default private List<ClaimVerificationItem> claims = List.of();

  /** claim별 검증 결과 항목 */
  @Getter
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ClaimVerificationItem {

    private UUID claimId;

    /** FE 매핑: Phase 67에서 claim 키로 rename (C-6) */
    private String claimText;

    private String speakerName;

    /** F-03: Lombok is-getter Jackson 키 보존 */
    @JsonProperty("isQuotedClaim")
    private boolean isQuotedClaim;

    private String originalContext;

    /** null = 미검증 claim */
    private Short tier;

    /** null = 비판정/미검증. FE 매핑: confidence (C-02) */
    private Short score;

    /** Verdict.name(). VerificationResult 존재 시 NOT NULL */
    private String verdict;

    private String reason;

    /** Tier2만 원문, 아니면 null */
    private String disclaimer;

    /** claim 단위 검증시각 */
    private LocalDateTime verifiedAt;

    /** 도출값 (SCORABLE이고 score!=null), 아니면 null */
    private String truthLabel;

    /** 도출값 (비판정), SCORABLE이면 null. FE 매핑: status (RC-06) */
    private String claimScoreStatus;

    /** 66b 빈 배열 계약 (C-5) */
    @Builder.Default private List<Object> evidence = List.of();
  }
}
