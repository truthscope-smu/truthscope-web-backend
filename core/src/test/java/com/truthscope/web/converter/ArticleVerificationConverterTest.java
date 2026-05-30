package com.truthscope.web.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.dto.response.ArticleVerificationResponse.ClaimVerificationItem;
import com.truthscope.web.dto.response.ClaimItemSource;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.entity.enums.Verdict;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ArticleVerificationConverter 단위 테스트 (T6 선택).
 *
 * <p>PLAN 테스트 전략 Unit 반영: claim 매핑(text/speaker/isQuotedClaim/verdict/disclaimer/verifiedAt),
 * evidence=[], sourceTransparency=null.
 *
 * <p>Converter는 Spring bean이 아닌 static 순수 변환 유틸리티이므로 Mockito 없이 직접 호출한다. buildResult는 tier/verdict를
 * 파라미터로 받아 테스트별 의도(Tier 1/2, verdict 종류)를 명시한다 (CodeRabbit 리뷰 반영).
 */
@DisplayName("ArticleVerificationConverter 단위 테스트")
class ArticleVerificationConverterTest {

  // -----------------------------------------------------------------------------------------
  // 헬퍼 팩토리
  // -----------------------------------------------------------------------------------------

  private AnalysisSession buildSession(Short totalScore) {
    return AnalysisSession.builder()
        .id(UUID.randomUUID())
        .status(SessionStatus.COMPLETED)
        .totalScore(totalScore)
        .completedAt(LocalDateTime.of(2026, 5, 30, 12, 0, 0))
        .member(null)
        .build();
  }

  private Article buildArticle(AnalysisSession session) {
    Article article =
        Article.extract(
            "https://news.example.com/article/1", "기사 제목", "본문 텍스트", "ko", "news.example.com");
    if (session != null) {
      article.attachTo(session);
    }
    return article;
  }

  private Claim buildClaim(
      UUID id,
      String text,
      String speakerName,
      boolean isQuotedClaim,
      String originalContext,
      Short sortOrder) {
    return Claim.builder()
        .id(id)
        .text(text)
        .speakerName(speakerName)
        .isQuotedClaim(isQuotedClaim)
        .originalContext(originalContext)
        .sortOrder(sortOrder)
        .build();
  }

  /** tier/verdict를 명시 파라미터로 받는다 — Tier 1/2 + verdict 종류별 의도를 테스트에서 직접 표현 (CodeRabbit 리뷰 반영). */
  private VerificationResult buildResult(
      Claim claim,
      Short tier,
      Verdict verdict,
      Short score,
      String disclaimer,
      LocalDateTime verifiedAt) {
    return VerificationResult.builder()
        .id(UUID.randomUUID())
        .claim(claim)
        .tier(tier)
        .verdict(verdict)
        .score(score)
        .reason("검증 근거")
        .disclaimer(disclaimer)
        .verifiedAt(verifiedAt)
        .build();
  }

  // -----------------------------------------------------------------------------------------
  // 1. claim 매핑 기본 필드
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("claim 매핑 (text/speaker/isQuotedClaim/verdict/disclaimer/verifiedAt)")
  class ClaimMapping {

    @Test
    @DisplayName("claimText, speakerName, isQuotedClaim, originalContext가 Claim에서 올바르게 매핑된다")
    void claim_기본_필드_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();
      LocalDateTime verifiedAt = LocalDateTime.of(2026, 5, 30, 10, 0, 0);

      Claim claim = buildClaim(claimId, "정부 발표 claim 텍스트", "홍길동", true, "원문 맥락", (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 2, Verdict.SUPPORTED, (short) 75, null, verifiedAt);
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims()).hasSize(1);
      ClaimVerificationItem item = response.getClaims().get(0);
      assertThat(item.getClaimText()).isEqualTo("정부 발표 claim 텍스트");
      assertThat(item.getSpeakerName()).isEqualTo("홍길동");
      assertThat(item.isQuotedClaim()).isTrue();
      assertThat(item.getOriginalContext()).isEqualTo("원문 맥락");
    }

    @Test
    @DisplayName("verdict가 VerificationResult.verdict.name()으로 매핑된다")
    void verdict_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "claim", null, false, null, (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 2, Verdict.SUPPORTED, (short) 75, null, LocalDateTime.now());
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims().get(0).getVerdict()).isEqualTo("SUPPORTED");
    }

    @Test
    @DisplayName("Tier 2 disclaimer가 그대로 매핑된다")
    void disclaimer_Tier2_매핑() {
      AnalysisSession session = buildSession((short) 70);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();
      String disclaimerText = "AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요.";

      Claim claim = buildClaim(claimId, "AI 분석 claim", null, false, null, (short) 0);
      VerificationResult result =
          buildResult(
              claim, (short) 2, Verdict.SUPPORTED, (short) 70, disclaimerText, LocalDateTime.now());
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims().get(0).getDisclaimer()).isEqualTo(disclaimerText);
    }

    @Test
    @DisplayName("Tier 1(disclaimer=null)이면 disclaimer null")
    void disclaimer_Tier1_null() {
      AnalysisSession session = buildSession((short) 80);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "Tier 1 claim", null, false, null, (short) 0);
      // CodeRabbit 반영: 실제 Tier 1(tier=1) result로 검증 — 이름과 fixture 일치
      VerificationResult result =
          buildResult(claim, (short) 1, Verdict.SUPPORTED, (short) 80, null, LocalDateTime.now());
      ClaimItemSource source = new ClaimItemSource(claim, result, "FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "FACT", List.of(source));

      assertThat(response.getClaims().get(0).getDisclaimer()).isNull();
      assertThat(response.getClaims().get(0).getTier()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("verifiedAt이 VerificationResult에서 올바르게 매핑된다")
    void verifiedAt_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();
      LocalDateTime verifiedAt = LocalDateTime.of(2026, 5, 30, 9, 30, 0);

      Claim claim = buildClaim(claimId, "claim", null, false, null, (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 2, Verdict.SUPPORTED, (short) 75, null, verifiedAt);
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims().get(0).getVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    @DisplayName("isQuotedClaim=false인 경우도 올바르게 매핑된다")
    void isQuotedClaim_false_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "인용 아닌 claim", null, false, null, (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 2, Verdict.SUPPORTED, (short) 75, null, LocalDateTime.now());
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims().get(0).isQuotedClaim()).isFalse();
    }
  }

  // -----------------------------------------------------------------------------------------
  // 2. evidence=[] 계약 (C-5)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("evidence 빈 배열 계약 (C-5)")
  class EvidenceContract {

    @Test
    @DisplayName("66a: VerificationResult 있는 claim의 evidence는 빈 배열")
    void evidence_빈_배열() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "claim", null, false, null, (short) 0);
      VerificationResult result =
          buildResult(claim, (short) 2, Verdict.SUPPORTED, (short) 75, null, LocalDateTime.now());
      ClaimItemSource source = new ClaimItemSource(claim, result, "MOSTLY_FACT", null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of(source));

      assertThat(response.getClaims().get(0).getEvidence()).isEmpty();
    }

    @Test
    @DisplayName("66a: VerificationResult null(미검증) claim의 evidence도 빈 배열")
    void evidence_미검증_claim도_빈_배열() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "미검증 claim", null, false, null, (short) 0);
      // result=null (미검증)
      ClaimItemSource source = new ClaimItemSource(claim, null, null, null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, null, List.of(source));

      assertThat(response.getClaims().get(0).getEvidence()).isEmpty();
    }
  }

  // -----------------------------------------------------------------------------------------
  // 3. sourceTransparency=null 계약 (66a)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("sourceTransparency null 계약 (66a)")
  class SourceTransparencyContract {

    @Test
    @DisplayName("66a: sourceTransparency는 항상 null (66b에서 채움)")
    void sourceTransparency_null() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of());

      assertThat(response.getSourceTransparency()).isNull();
    }
  }

  // -----------------------------------------------------------------------------------------
  // 4. 기사 단위 필드 매핑
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("기사 단위 필드 매핑 (articleId/url/title/status/analysisCompletedAt/totalScore)")
  class ArticleFieldMapping {

    @Test
    @DisplayName("articleId, url, title이 Article에서 올바르게 매핑된다")
    void article_기본_필드_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of());

      assertThat(response.getUrl()).isEqualTo("https://news.example.com/article/1");
      assertThat(response.getTitle()).isEqualTo("기사 제목");
      assertThat(response.getArticleId()).isEqualTo(article.getId());
    }

    @Test
    @DisplayName("status가 SessionStatus.name()으로 매핑된다")
    void session_status_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of());

      assertThat(response.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("analysisCompletedAt이 session.completedAt으로 매핑된다")
    void analysisCompletedAt_매핑() {
      LocalDateTime completedAt = LocalDateTime.of(2026, 5, 30, 12, 0, 0);
      AnalysisSession session =
          AnalysisSession.builder()
              .id(UUID.randomUUID())
              .status(SessionStatus.COMPLETED)
              .totalScore((short) 75)
              .completedAt(completedAt)
              .member(null)
              .build();
      Article article = buildArticle(session);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", List.of());

      assertThat(response.getAnalysisCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("totalScore null이면 DTO totalScore도 null")
    void totalScore_null_매핑() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticle(session);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, null, List.of());

      assertThat(response.getTotalScore()).isNull();
      assertThat(response.getArticleLabel()).isNull();
    }

    @Test
    @DisplayName("claims가 여러 건이면 순서대로 매핑된다")
    void claims_여러건_순서_매핑() {
      AnalysisSession session = buildSession((short) 75);
      Article article = buildArticle(session);

      UUID id0 = UUID.randomUUID();
      UUID id1 = UUID.randomUUID();
      Claim claim0 = buildClaim(id0, "첫번째 claim", null, false, null, (short) 0);
      Claim claim1 = buildClaim(id1, "두번째 claim", null, false, null, (short) 1);

      VerificationResult result0 =
          buildResult(claim0, (short) 1, Verdict.SUPPORTED, (short) 80, null, LocalDateTime.now());
      VerificationResult result1 =
          buildResult(claim1, (short) 2, Verdict.SUPPORTED, (short) 65, null, LocalDateTime.now());

      List<ClaimItemSource> sources =
          List.of(
              new ClaimItemSource(claim0, result0, "FACT", null),
              new ClaimItemSource(claim1, result1, "MOSTLY_FACT", null));

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, "MOSTLY_FACT", sources);

      assertThat(response.getClaims()).hasSize(2);
      assertThat(response.getClaims().get(0).getClaimText()).isEqualTo("첫번째 claim");
      assertThat(response.getClaims().get(1).getClaimText()).isEqualTo("두번째 claim");
      assertThat(response.getClaims().get(0).getTruthLabel()).isEqualTo("FACT");
      assertThat(response.getClaims().get(1).getTruthLabel()).isEqualTo("MOSTLY_FACT");
    }
  }

  // -----------------------------------------------------------------------------------------
  // 5. result null (미검증 claim)
  // -----------------------------------------------------------------------------------------

  @Nested
  @DisplayName("result null (미검증 claim) 매핑")
  class NullResultMapping {

    @Test
    @DisplayName("result null이면 tier/score/verdict/reason/disclaimer/verifiedAt 모두 null")
    void result_null이면_관련_필드_null() {
      AnalysisSession session = buildSession(null);
      Article article = buildArticle(session);
      UUID claimId = UUID.randomUUID();

      Claim claim = buildClaim(claimId, "미검증 claim", "발언자", true, "맥락", (short) 0);
      ClaimItemSource source = new ClaimItemSource(claim, null, null, null);

      ArticleVerificationResponse response =
          ArticleVerificationConverter.toResponse(article, session, null, List.of(source));

      ClaimVerificationItem item = response.getClaims().get(0);
      assertThat(item.getClaimText()).isEqualTo("미검증 claim");
      assertThat(item.getSpeakerName()).isEqualTo("발언자");
      assertThat(item.isQuotedClaim()).isTrue();
      assertThat(item.getTier()).isNull();
      assertThat(item.getScore()).isNull();
      assertThat(item.getVerdict()).isNull();
      assertThat(item.getReason()).isNull();
      assertThat(item.getDisclaimer()).isNull();
      assertThat(item.getVerifiedAt()).isNull();
      assertThat(item.getTruthLabel()).isNull();
      assertThat(item.getClaimScoreStatus()).isNull();
      assertThat(item.getEvidence()).isEmpty();
    }
  }
}
