package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.entity.enums.Verdict;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ScoreBandPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ArticleVerificationService 단위 테스트 (T6).
 *
 * <p>PLAN 테스트 전략 Unit 반영: totalScore-&gt;articleLabel 밴드 도출, score-&gt;truthLabel,
 * tier3Reason-&gt;claimScoreStatus, totalScore null이면 articleLabel null, R-004 score=null이면 NPE 없이
 * truthLabel null, RC-01 session null이면 NotFoundException(404), articleId 미존재 404, member null 공개
 * 허용, RC-06 SCORABLE이면 claimScoreStatus null.
 *
 * <p>회귀 시뮬레이션 무력화 A fixture: totalScore=70 (MOSTLY_FACT 밴드 60-79). factMin 기본값=80이므로
 * totalScore&gt;=80이면 FACT가 원래 맞아 상수 치환 FAIL 검출 불가.
 *
 * <p>참고: claim 0건 테스트는 H3 조기 반환으로 findByClaimIdIn을 호출하지 않으므로 해당 stub을 두지 않는다(strict Mockito
 * UnnecessaryStubbing 회피).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleVerificationService 단위 테스트")
class ArticleVerificationServiceTest {

  @Mock private ArticleRepository articleRepository;
  @Mock private ClaimRepository claimRepository;
  @Mock private VerificationResultRepository verificationResultRepository;

  // ScoreBandPolicy(80, 60, 40, 20) — 기본값과 동일. MOSTLY_FACT 밴드: 60..79
  private static final ScoreBandPolicy BAND_POLICY = new ScoreBandPolicy(80, 60, 40, 20);

  private ArticleVerificationService service;

  @BeforeEach
  void setUp() {
    service =
        new ArticleVerificationService(
            articleRepository, claimRepository, verificationResultRepository, BAND_POLICY);
  }

  // -----------------------------------------------------------------------------------------
  // 헬퍼 팩토리
  // -----------------------------------------------------------------------------------------

  /** 분석 세션에 부착된 기사 생성. Article.extract() 사용(static factory — DDD 원칙). */
  private Article buildArticleWithSession(AnalysisSession session) {
    Article article =
        Article.extract("https://example.com/news", "테스트 기사", "본문", "ko", "example.com");
    article.attachTo(session);
    return article;
  }

  /** 세션에 부착되지 않은 기사 생성 (session null 가드 테스트용). */
  private Article buildArticleWithoutSession() {
    return Article.extract("https://example.com/news", "세션 없는 기사", "본문", "ko", "example.com");
  }

  /**
   * COMPLETED 세션 생성. totalScore 지정.
   *
   * <p>completeCascade를 직접 호출할 수 없으므로 Builder로 필드를 직접 채운다. member 필드는 null로 세팅(익명 공개).
   */
  private AnalysisSession buildSession(Short totalScore) {
    return AnalysisSession.builder()
        .id(UUID.randomUUID())
        .status(SessionStatus.COMPLETED)
        .totalScore(totalScore)
        .completedAt(LocalDateTime.now())
        .member(null) // 익명 세션 (공개 허용 MVP)
        .build();
  }

  private Claim buildClaim(UUID id, String text, Short sortOrder) {
    return Claim.builder().id(id).text(text).sortOrder(sortOrder).isQuotedClaim(false).build();
  }

  /**
   * VerificationResult 생성.
   *
   * <p>claim 연결은 claim 필드 직접 설정 — Claim.getId()를 resultMap 키로 사용하므로 claim.id와 result.claim.id가 일치해야
   * 한다.
   */
  private VerificationResult buildResult(Claim claim, Short score, Tier3Reason tier3Reason) {
    return VerificationResult.builder()
        .id(UUID.randomUUID())
        .claim(claim)
        .tier((short) 1)
        .verdict(Verdict.SUPPORTED)
        .score(score)
        .tier3Reason(tier3Reason)
        .verifiedAt(LocalDateTime.now())
        .build();
  }

  // -----------------------------------------------------------------------------------------
  // 1. 기사 단위 articleLabel 도출 (totalScore -> articleLabel 밴드)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("articleLabel 도출 (totalScore 밴드)")
  class ArticleLabelDerivation {

    @Test
    @DisplayName("totalScore=70이면 MOSTLY_FACT (회귀 시뮬 무력화 A fixture)")
    void totalScore_70이면_MOSTLY_FACT() {
      // 회귀 무력화 A fixture: totalScore=70 (MOSTLY_FACT 밴드 60-79)
      // 상수 "FACT"로 치환 시 expected: MOSTLY_FACT but was: FACT
      AnalysisSession session = buildSession((short) 70);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId)).thenReturn(List.of());

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getArticleLabel()).isEqualTo("MOSTLY_FACT");
    }

    @Test
    @DisplayName("totalScore=80이면 FACT")
    void totalScore_80이면_FACT() {
      AnalysisSession session = buildSession((short) 80);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId)).thenReturn(List.of());

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getArticleLabel()).isEqualTo("FACT");
    }

    @Test
    @DisplayName("totalScore null이면 articleLabel null (검증 가능 claim 0건)")
    void totalScore_null이면_articleLabel_null() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId)).thenReturn(List.of());

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getArticleLabel()).isNull();
    }
  }

  // -----------------------------------------------------------------------------------------
  // 2. claim 단위 truthLabel 도출 (score -> truthLabel)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("claim truthLabel 도출 (score 밴드)")
  class ClaimTruthLabelDerivation {

    @Test
    @DisplayName("score=75이면 truthLabel=MOSTLY_FACT (tier3Reason null이므로 SCORABLE 경로)")
    void score_75이면_truthLabel_MOSTLY_FACT() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "테스트 claim", (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 75, null); // tier3Reason=null → SCORABLE

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims()).hasSize(1);
      assertThat(response.getClaims().get(0).getTruthLabel()).isEqualTo("MOSTLY_FACT");
    }

    @Test
    @DisplayName("score=80이면 truthLabel=FACT")
    void score_80이면_truthLabel_FACT() {
      AnalysisSession session = buildSession((short) 80);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "팩트 claim", (short) 0);
      VerificationResult result = buildResult(claim, (short) 80, null);

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims().get(0).getTruthLabel()).isEqualTo("FACT");
    }

    @Test
    @DisplayName("R-004: score=null이면 NPE 없이 truthLabel=null")
    void score_null이면_NPE_없이_truthLabel_null() {
      // R-004 score null 언박싱 방어 — Short rawScore 언박싱 NPE 발생하면 안 됨
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "비판정 claim", (short) 0);
      // score=null, tier3Reason=INSUFFICIENT
      VerificationResult result = buildResult(claim, null, Tier3Reason.INSUFFICIENT);

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      // NPE가 발생하지 않아야 함
      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims()).hasSize(1);
      assertThat(response.getClaims().get(0).getTruthLabel()).isNull();
    }
  }

  // -----------------------------------------------------------------------------------------
  // 3. tier3Reason -> claimScoreStatus 도출
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("claimScoreStatus 도출 (tier3Reason 매핑)")
  class ClaimScoreStatusDerivation {

    @Test
    @DisplayName("tier3Reason=INSUFFICIENT이면 claimScoreStatus=INSUFFICIENT")
    void tier3Reason_INSUFFICIENT이면_claimScoreStatus_INSUFFICIENT() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "근거 부족 claim", (short) 0);
      VerificationResult result = buildResult(claim, null, Tier3Reason.INSUFFICIENT);

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims().get(0).getClaimScoreStatus()).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("tier3Reason=TIME_SENSITIVE이면 claimScoreStatus=TIME_SENSITIVE")
    void tier3Reason_TIME_SENSITIVE이면_claimScoreStatus_TIME_SENSITIVE() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "시점 의존 claim", (short) 0);
      VerificationResult result = buildResult(claim, null, Tier3Reason.TIME_SENSITIVE);

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims().get(0).getClaimScoreStatus()).isEqualTo("TIME_SENSITIVE");
    }

    @Test
    @DisplayName("tier3Reason=OUT_OF_SCOPE이면 claimScoreStatus=OUT_OF_SCOPE")
    void tier3Reason_OUT_OF_SCOPE이면_claimScoreStatus_OUT_OF_SCOPE() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "범위 외 claim", (short) 0);
      VerificationResult result = buildResult(claim, null, Tier3Reason.OUT_OF_SCOPE);

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims().get(0).getClaimScoreStatus()).isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    @DisplayName(
        "RC-06: tier3Reason=null(SCORABLE)이면 claimScoreStatus=null, truthLabel만 set (상호 배타)")
    void tier3Reason_null이면_claimScoreStatus_null_RC06() {
      // PLAN RC-06: SCORABLE이면 DTO claimScoreStatus는 null, truthLabel과 상호 배타.
      // 서비스가 deriveStatus()=SCORABLE을 null로 치환한다.
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "정상 판정 claim", (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 75, null); // tier3Reason=null → SCORABLE

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId)))
          .thenReturn(List.of(result));

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims().get(0).getClaimScoreStatus())
          .isNull(); // RC-06: SCORABLE → null
      assertThat(response.getClaims().get(0).getTruthLabel()).isEqualTo("MOSTLY_FACT"); // 상호 배타
    }
  }

  // -----------------------------------------------------------------------------------------
  // 4. 가드 검증 (RC-01, 404, 공개 허용)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("가드 및 404 처리")
  class Guards {

    @Test
    @DisplayName("RC-01: session null이면 NotFoundException(404)")
    void session_null이면_NotFoundException() {
      // Article은 존재하지만 session이 null인 상태
      Article article = buildArticleWithoutSession();
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));

      assertThatExceptionOfType(NotFoundException.class)
          .isThrownBy(() -> service.getVerification(articleId))
          .withMessageContaining("분석 세션이 없습니다");
    }

    @Test
    @DisplayName("articleId 미존재이면 NotFoundException(404)")
    void articleId_미존재이면_NotFoundException() {
      UUID unknownId = UUID.randomUUID();

      when(articleRepository.findById(unknownId)).thenReturn(Optional.empty());

      assertThatExceptionOfType(NotFoundException.class)
          .isThrownBy(() -> service.getVerification(unknownId))
          .withMessageContaining("기사를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("member null 세션(익명)이면 403 throw 없이 정상 응답 (공개 허용 MVP)")
    void member_null_이면_공개_허용() {
      // session.getMember()=null → 익명 세션 → 403 throw 없음 (OpenAPI 403 미기재)
      AnalysisSession session = buildSession((short) 70); // member=null (buildSession 기본값)
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId)).thenReturn(List.of());

      // NotFoundException 또는 다른 예외가 발생하지 않아야 함
      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response).isNotNull();
      assertThat(response.getArticleId()).isEqualTo(articleId);
    }

    @Test
    @DisplayName("claims 없으면 응답 claims 빈 리스트")
    void claims_없으면_빈_리스트() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId)).thenReturn(List.of());

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims()).isEmpty();
    }

    @Test
    @DisplayName("VerificationResult 없는 claim(미검증)이면 truthLabel/claimScoreStatus null")
    void verificationResult_없으면_미검증_claim() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticleWithSession(session);
      UUID articleId = article.getId();
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "미검증 claim", (short) 0);
      // result 없음 — findByClaimIdIn 결과 비어있음

      when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
      when(claimRepository.findByArticleIdOrderBySortOrderAsc(articleId))
          .thenReturn(List.of(claim));
      when(verificationResultRepository.findByClaimIdIn(List.of(claimId))).thenReturn(List.of());

      ArticleVerificationResponse response = service.getVerification(articleId);

      assertThat(response.getClaims()).hasSize(1);
      assertThat(response.getClaims().get(0).getTruthLabel()).isNull();
      assertThat(response.getClaims().get(0).getClaimScoreStatus()).isNull();
      assertThat(response.getClaims().get(0).getVerdict()).isNull();
    }
  }
}
