package com.truthscope.web.converter;

import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.dto.response.ArticleVerificationResponse.ClaimVerificationItem;
import com.truthscope.web.dto.response.ClaimItemSource;
import com.truthscope.web.dto.response.EvidenceDto;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.scoring.SourceTransparencySummary;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Article + AnalysisSession + ClaimItemSource 목록 → ArticleVerificationResponse 순수 변환 유틸리티.
 *
 * <p>집계·도출 호출 없음 — 서비스가 계산한 값을 전달받아 매핑만 수행한다 (CONVENTIONS Converter 규칙).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ArticleVerificationConverter {

  /**
   * Article, AnalysisSession, 도출된 articleLabel, ClaimItemSource 목록, sourceTransparency 집계 결과를
   * ArticleVerificationResponse로 변환한다.
   *
   * <p>66b T8: sourceTransparency는 서비스가 tier 재구성으로 계산한 값을 전달받는다. evidence는
   * ClaimItemSource.sources()에서 EvidenceDto로 변환한다. Converter는 순수 변환만 수행하며 집계·도출 계산을 직접 수행하지 않는다.
   *
   * @param article 대상 기사 엔티티
   * @param session 기사에 연결된 분석 세션
   * @param articleLabel TruthLabel.name() 도출값, null 가능 (totalScore null이면 null)
   * @param claimSources claim별 검증 결과 소스 목록 (서비스가 sources 포함 구성 후 전달)
   * @param sourceTransparency 서비스가 tier 재구성으로 집계한 출처 투명성 요약, null 가능
   */
  public static ArticleVerificationResponse toResponse(
      Article article,
      AnalysisSession session,
      String articleLabel,
      List<ClaimItemSource> claimSources,
      SourceTransparencySummary sourceTransparency) {

    List<ClaimVerificationItem> claimItems =
        claimSources.stream().map(ArticleVerificationConverter::toClaimItem).toList();

    return ArticleVerificationResponse.builder()
        .articleId(article.getId())
        .url(article.getUrl())
        .title(article.getTitle())
        .status(session.getStatus().name())
        .analysisCompletedAt(session.getCompletedAt())
        .totalScore(session.getTotalScore())
        .articleLabel(articleLabel)
        .coverage(session.getCoverage())
        .tier1Count(session.getTier1Count())
        .tier2Count(session.getTier2Count())
        .tier3Count(session.getTier3Count())
        .sourceTransparency(sourceTransparency)
        .claims(claimItems)
        .build();
  }

  private static ClaimVerificationItem toClaimItem(ClaimItemSource source) {
    Claim claim = source.claim();
    VerificationResult result = source.result();

    List<EvidenceDto> evidence =
        source.sources() != null
            ? source.sources().stream().map(EvidenceDto::from).toList()
            : List.of();

    return ClaimVerificationItem.builder()
        .claimId(claim.getId())
        .claimText(claim.getText())
        .speakerName(claim.getSpeakerName())
        .isQuotedClaim(claim.isQuotedClaim())
        .originalContext(claim.getOriginalContext())
        .tier(result != null ? result.getTier() : null)
        .score(result != null ? result.getScore() : null)
        // M1: verdict는 DB NOT NULL이나 Java 객체 수준 null 방어
        .verdict(result != null && result.getVerdict() != null ? result.getVerdict().name() : null)
        .reason(result != null ? result.getReason() : null)
        .disclaimer(result != null ? result.getDisclaimer() : null)
        .verifiedAt(result != null ? result.getVerifiedAt() : null)
        .truthLabel(source.truthLabel())
        .claimScoreStatus(source.claimScoreStatus())
        .evidence(evidence)
        .build();
  }
}
