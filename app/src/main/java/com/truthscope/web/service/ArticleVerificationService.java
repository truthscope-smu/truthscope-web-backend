package com.truthscope.web.service;

import com.truthscope.web.converter.ArticleVerificationConverter;
import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.dto.response.ClaimItemSource;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.scoring.TruthLabelDeriver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기사 검증 결과 조회 서비스 (UC-140 결과 카드 표시, EO).
 *
 * <p>T4: articleId → ArticleVerificationResponse. session null 가드(RC-01), 소유권 골격(UC-140 A1),
 * per-claim 도출(R-004 score null 언박싱 방어, RC-06 SCORABLE 상호 배타).
 */
@Service
@RequiredArgsConstructor
public class ArticleVerificationService {

  private final ArticleRepository articleRepository;
  private final ClaimRepository claimRepository;
  private final VerificationResultRepository verificationResultRepository;
  private final ScoreBandPolicy bandPolicy; // ScoringPolicyConfig @Bean (AnalysisService와 동일)

  /**
   * 기사 ID로 검증 결과를 조회한다.
   *
   * @param articleId 조회할 기사 ID
   * @return ArticleVerificationResponse
   * @throws NotFoundException 기사 또는 분석 세션이 존재하지 않는 경우
   */
  @Transactional(readOnly = true)
  public ArticleVerificationResponse getVerification(UUID articleId) {
    Article article =
        articleRepository
            .findById(articleId)
            .orElseThrow(() -> new NotFoundException("기사를 찾을 수 없습니다"));

    AnalysisSession session = article.getSession();
    if (session == null) { // RC-01 Critical
      throw new NotFoundException("기사에 분석 세션이 없습니다: " + articleId);
    }

    // 소유권 골격(UC-140 A1): session.getMember()!=null && 인증사용자 불일치면 403.
    //   현재 SecurityConfig.permitAll + 인증 principal 부재 -> 익명/소유자없음 공개 허용(MVP).
    //   throw 없음 -> OpenAPI 403 미기재. 인증 배선 phase에서 ForbiddenException + throw 추가.

    String articleLabel = deriveArticleLabel(session.getTotalScore());
    List<Claim> claims = claimRepository.findByArticleIdOrderBySortOrderAsc(articleId);
    List<ClaimItemSource> claimSources = buildClaimSources(claims);

    return ArticleVerificationConverter.toResponse(article, session, articleLabel, claimSources);
  }

  /** 기사 단위 라벨 도출. totalScore null이면 null (검증 가능 claim 0건). */
  private String deriveArticleLabel(Short totalScore) {
    if (totalScore == null) {
      return null;
    }
    return TruthLabelDeriver.deriveTruthLabel(totalScore.intValue(), bandPolicy).name();
  }

  /**
   * claim 목록을 ClaimItemSource 목록으로 구성한다.
   *
   * <p>H3: claim 0건이면 빈 IN 절(PostgreSQL syntax error) 방지 + 불필요한 조회 생략. bulk 조회(C-01/F-02 N+1 완화)는
   * JOIN FETCH로 claim을 함께 로드하므로 {@code r.getClaim().getId()} 접근 시 추가 쿼리가 없다(H2).
   */
  private List<ClaimItemSource> buildClaimSources(List<Claim> claims) {
    if (claims.isEmpty()) {
      return List.of();
    }
    List<UUID> claimIds = claims.stream().map(Claim::getId).toList();
    Map<UUID, VerificationResult> resultMap =
        verificationResultRepository.findByClaimIdIn(claimIds).stream()
            .collect(Collectors.toMap(r -> r.getClaim().getId(), Function.identity()));
    return claims.stream()
        .map(claim -> toClaimSource(claim, resultMap.get(claim.getId())))
        .toList();
  }

  /** 단일 claim + 검증 결과를 ClaimItemSource로 변환한다 (truthLabel/claimScoreStatus 도출 포함). */
  private ClaimItemSource toClaimSource(Claim claim, VerificationResult result) {
    // R-004 score null 언박싱 방어 — 명시 블록
    Short rawScore = Optional.ofNullable(result).map(VerificationResult::getScore).orElse(null);
    String truthLabel =
        (rawScore != null)
            ? TruthLabelDeriver.deriveTruthLabel(rawScore.intValue(), bandPolicy).name()
            : null;

    // H1/RC-06: SCORABLE이면 claimScoreStatus=null (truthLabel과 상호 배타)
    ClaimScoreStatus derivedStatus =
        (result != null) ? deriveStatus(result.getTier3Reason()) : null;
    String claimScoreStatus =
        (derivedStatus != null && derivedStatus != ClaimScoreStatus.SCORABLE)
            ? derivedStatus.name()
            : null;

    return new ClaimItemSource(claim, result, truthLabel, claimScoreStatus);
  }

  /**
   * Tier3Reason → ClaimScoreStatus 매핑 헬퍼.
   *
   * <p>tier3Reason=null이면 SCORABLE (Tier 1/2 판정 완료). 비판정(INSUFFICIENT/TIME_SENSITIVE/OUT_OF_SCOPE)은
   * 대응 status를 반환한다. 호출 측에서 SCORABLE인 경우 DTO claimScoreStatus는 null로 세팅된다 (RC-06 상호 배타).
   *
   * @param tier3Reason VerificationResult.tier3Reason, null이면 SCORABLE
   * @return 대응 ClaimScoreStatus
   */
  private static ClaimScoreStatus deriveStatus(Tier3Reason tier3Reason) {
    if (tier3Reason == null) {
      return ClaimScoreStatus.SCORABLE;
    }
    return switch (tier3Reason) {
      case INSUFFICIENT -> ClaimScoreStatus.INSUFFICIENT;
      case TIME_SENSITIVE -> ClaimScoreStatus.TIME_SENSITIVE;
      case OUT_OF_SCOPE -> ClaimScoreStatus.OUT_OF_SCOPE;
    };
  }
}
