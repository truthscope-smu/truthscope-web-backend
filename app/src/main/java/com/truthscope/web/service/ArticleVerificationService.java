package com.truthscope.web.service;

import com.truthscope.web.converter.ArticleVerificationConverter;
import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.dto.response.ClaimItemSource;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.repository.VerifySourceRepository;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.ScoreBandPolicy;
import com.truthscope.web.scoring.SourceTransparency;
import com.truthscope.web.scoring.SourceTransparencyAggregator;
import com.truthscope.web.scoring.SourceTransparencySummary;
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
  private final VerifySourceRepository verifySourceRepository;
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

    // 66b T8: tier 재구성으로 sourceTransparency 집계 (M-1 해소, 무마이그레이션)
    SourceTransparencySummary sourceTransparency = reconstructSourceTransparency(claimSources);

    return ArticleVerificationConverter.toResponse(
        article, session, articleLabel, claimSources, sourceTransparency);
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
   *
   * <p>66b T8: VerificationResult ID 목록으로 VerifySource를 일괄 조회하고(N+1 방지 bulk — codex#6) result_id별로
   * 그룹핑해 각 claim의 sources 목록을 구성한다.
   */
  private List<ClaimItemSource> buildClaimSources(List<Claim> claims) {
    if (claims.isEmpty()) {
      return List.of();
    }
    List<UUID> claimIds = claims.stream().map(Claim::getId).toList();
    Map<UUID, VerificationResult> resultMap =
        verificationResultRepository.findByClaimIdIn(claimIds).stream()
            .collect(Collectors.toMap(r -> r.getClaim().getId(), Function.identity()));

    // 66b T8: VerifySource bulk 조회 (N+1 방지)
    List<UUID> resultIds = resultMap.values().stream().map(VerificationResult::getId).toList();
    Map<UUID, List<VerifySource>> sourcesByResultId =
        resultIds.isEmpty()
            ? Map.of()
            : verifySourceRepository.findByResultIdIn(resultIds).stream()
                .collect(Collectors.groupingBy(vs -> vs.getResult().getId()));

    return claims.stream()
        .map(
            claim -> {
              VerificationResult result = resultMap.get(claim.getId());
              List<VerifySource> sources =
                  result != null
                      ? sourcesByResultId.getOrDefault(result.getId(), List.of())
                      : List.of();
              return toClaimSource(claim, result, sources);
            })
        .toList();
  }

  /**
   * 단일 claim + 검증 결과 + 출처 목록을 ClaimItemSource로 변환한다 (truthLabel/claimScoreStatus 도출 포함).
   *
   * <p>66b T8: sources 인자를 추가해 5-arg ClaimItemSource 생성.
   */
  private ClaimItemSource toClaimSource(
      Claim claim, VerificationResult result, List<VerifySource> sources) {
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

    return new ClaimItemSource(claim, result, truthLabel, claimScoreStatus, sources);
  }

  /**
   * ClaimItemSource 목록에서 tier 재구성으로 SourceTransparencySummary를 집계한다 (66b T8, M-1 해소).
   *
   * <p>tier→SourceTransparency 결정적 매핑 (VerificationCascadeService:78/98/109/138과 정합):
   *
   * <ul>
   *   <li>tier 1 → EXPLICIT
   *   <li>tier 2 → AMBIGUOUS
   *   <li>tier 3 또는 result null → NONE
   * </ul>
   *
   * <p>ClaimVerificationSignal 불변식 준수: SCORABLE이면 score 0..100 non-null, 비판정이면 score null. tier
   * 1/2에 score가 있으면 SCORABLE, 없으면 INSUFFICIENT로 설정한다.
   */
  private SourceTransparencySummary reconstructSourceTransparency(List<ClaimItemSource> sources) {
    if (sources.isEmpty()) {
      return SourceTransparencyAggregator.aggregateSourceTransparency(List.of());
    }
    List<ClaimVerificationSignal> signals =
        sources.stream().map(this::toVerificationSignal).toList();
    return SourceTransparencyAggregator.aggregateSourceTransparency(signals);
  }

  /** 단일 ClaimItemSource를 ClaimVerificationSignal로 변환한다 (tier 재구성 + 불변식 준수). */
  private ClaimVerificationSignal toVerificationSignal(ClaimItemSource source) {
    VerificationResult result = source.result();
    Claim claim = source.claim();

    if (result == null) {
      return new ClaimVerificationSignal(
          claim.getId(), (short) 3, null, ClaimScoreStatus.INSUFFICIENT, SourceTransparency.NONE);
    }

    Short tier = result.getTier();
    if (tier == null) {
      tier = (short) 3;
    }

    SourceTransparency transparency =
        switch (tier.intValue()) {
          case 1 -> SourceTransparency.EXPLICIT;
          case 2 -> SourceTransparency.AMBIGUOUS;
          default -> SourceTransparency.NONE;
        };

    Short rawScore = result.getScore();
    if (rawScore != null) {
      return new ClaimVerificationSignal(
          claim.getId(), tier, rawScore.intValue(), ClaimScoreStatus.SCORABLE, transparency);
    } else {
      return new ClaimVerificationSignal(
          claim.getId(), tier, null, ClaimScoreStatus.INSUFFICIENT, transparency);
    }
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
