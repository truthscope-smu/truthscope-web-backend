package com.truthscope.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.entity.enums.Verdict;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ArticleController.findVerification 통합 테스트 (T7).
 *
 * <p>PLAN 통합 테스트 전략 반영:
 *
 * <ul>
 *   <li>GET /api/v1/articles/{id}/verification 200 + JSON 단언
 *   <li>(rev.2) ISO-8601 직렬화 단언: $.claims[0].verifiedAt이 ISO 문자열(epoch 배열 아님)
 *   <li>(F-03) $.claims[0].isQuotedClaim 키 존재 단언
 *   <li>(C-5) $.claims[0].evidence == [] 단언
 *   <li>(F-05) claims[] sort_order 오름차순 단언
 *   <li>미존재 id 404 + GlobalExceptionHandler envelope
 * </ul>
 *
 * <p>fixture: VerificationResult 1건 이상 + sort_order 0/1/2 Claim 3건 + totalScore=70 (회귀 시뮬 A용
 * MOSTLY_FACT 밴드 60-79).
 *
 * <p>AbstractIntegrationTest 상속: @SpringBootTest(RANDOM_PORT) + Testcontainers PostgreSQL Singleton
 * + create-drop. MockMvc는 @AutoConfigureMockMvc로 주입.
 */
@AutoConfigureMockMvc
class ArticleVerificationControllerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private AnalysisSessionRepository sessionRepository;
  @Autowired private ClaimRepository claimRepository;
  @Autowired private VerificationResultRepository verificationResultRepository;

  // fixture 저장 엔티티
  private Article savedArticle;
  private AnalysisSession savedSession;
  private Claim savedClaim0;
  private Claim savedClaim1;
  private Claim savedClaim2;
  private VerificationResult savedResult;

  @BeforeEach
  void setup() {
    // FK 안전 순서: VerificationResult → Claim → Article → AnalysisSession
    verificationResultRepository.deleteAllInBatch();
    claimRepository.deleteAllInBatch();
    articleRepository.deleteAllInBatch();
    sessionRepository.deleteAllInBatch();

    // 회귀 시뮬 A fixture: totalScore=70 (MOSTLY_FACT 밴드 60-79).
    // factMin 기본값=80 이라 totalScore>=80이면 FACT 원래 맞아 상수 치환 FAIL 검출 불가.
    savedSession =
        sessionRepository.saveAndFlush(
            AnalysisSession.builder()
                .status(SessionStatus.COMPLETED)
                .requestedAt(LocalDateTime.now().minusMinutes(5))
                .completedAt(LocalDateTime.now().minusMinutes(1))
                .totalScore((short) 70)
                .tier1Count((short) 1)
                .tier2Count((short) 0)
                .tier3Count((short) 0)
                .member(null) // 익명 세션 (공개 허용 MVP)
                .build());

    savedArticle =
        articleRepository.saveAndFlush(
            Article.extract(
                "https://example.com/news/verification-integ",
                "검증 통합테스트 기사",
                "통합테스트 본문",
                "ko",
                "example.com"));
    // 세션 부착 (attachTo는 DDD 비즈니스 메서드 — setter 금지)
    savedArticle.attachTo(savedSession);
    savedArticle = articleRepository.saveAndFlush(savedArticle);

    // sort_order 0/1/2 Claim 3건 — F-05 순서 단언용
    savedClaim0 =
        claimRepository.saveAndFlush(
            Claim.builder()
                .article(savedArticle)
                .text("첫 번째 claim — sort_order=0")
                .sortOrder((short) 0)
                .isQuotedClaim(false)
                .build());
    savedClaim1 =
        claimRepository.saveAndFlush(
            Claim.builder()
                .article(savedArticle)
                .text("두 번째 claim — sort_order=1")
                .sortOrder((short) 1)
                .isQuotedClaim(true)
                .build());
    savedClaim2 =
        claimRepository.saveAndFlush(
            Claim.builder()
                .article(savedArticle)
                .text("세 번째 claim — sort_order=2")
                .sortOrder((short) 2)
                .isQuotedClaim(false)
                .build());

    // VerificationResult: claim0에만 부착 (1건 이상 fixture 요구 충족 + 회귀 시뮬 B 전제)
    savedResult =
        verificationResultRepository.saveAndFlush(
            VerificationResult.builder()
                .claim(savedClaim0)
                .tier((short) 1)
                .verdict(Verdict.SUPPORTED)
                .score((short) 70)
                .verifiedAt(LocalDateTime.now().minusMinutes(2))
                .build());
  }

  // ── 1. 200 happy path ────────────────────────────────────────────────────

  @Test
  @DisplayName("GET /api/v1/articles/{id}/verification — 200 + articleLabel MOSTLY_FACT")
  void findVerification_happyPath_200_articleLabel() throws Exception {
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.articleId").value(savedArticle.getId().toString()))
        .andExpect(jsonPath("$.articleLabel").value("MOSTLY_FACT"))
        .andExpect(jsonPath("$.totalScore").value(70))
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  @DisplayName("(rev.2) verifiedAt ISO-8601 직렬화 단언 — epoch 배열 아님")
  void findVerification_verifiedAt_isIso8601String_notEpochArray() throws Exception {
    // C-2 Critical: write-dates-as-timestamps=false 설정 단언
    // epoch 배열은 "[2026,5,30,...]" 형태, ISO는 "2026-05-30T..." 형태
    String body =
        mockMvc
            .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claims[0].verifiedAt").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // ISO-8601 패턴: "2026-..." 으로 시작하고 "T"를 포함해야 함 (epoch 배열 "[" 으로 시작하지 않음)
    assertThat(body).contains("\"verifiedAt\":");
    assertThat(body).doesNotContain("\"verifiedAt\":["); // epoch 배열 형태 아님
  }

  @Test
  @DisplayName("(F-03) isQuotedClaim 키 존재 단언 — Lombok is-getter Jackson 키 보존")
  void findVerification_isQuotedClaim_keyPresent() throws Exception {
    // F-03: @JsonProperty("isQuotedClaim") 적용 여부 — 미적용 시 키가 "quotedClaim"으로 나옴
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claims[0].isQuotedClaim").isBoolean())
        .andExpect(jsonPath("$.claims[0].isQuotedClaim").value(false))
        .andExpect(jsonPath("$.claims[1].isQuotedClaim").value(true));
  }

  @Test
  @DisplayName("(C-5) evidence=[] 빈 배열 계약 단언")
  void findVerification_evidence_isEmptyArray() throws Exception {
    // C-5: 66a 빈 배열 계약 — 66b에서 채움
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claims[0].evidence").isArray())
        .andExpect(jsonPath("$.claims[0].evidence").isEmpty());
  }

  @Test
  @DisplayName("(F-05) claims[] sort_order 오름차순 단언 — 0/1/2 순서")
  void findVerification_claims_orderedBySortOrderAsc() throws Exception {
    // F-05: findByArticleIdOrderBySortOrderAsc(@Query NULLS LAST) 정렬 검증
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claims").isArray())
        .andExpect(jsonPath("$.claims.length()").value(3))
        .andExpect(jsonPath("$.claims[0].claimText").value("첫 번째 claim — sort_order=0"))
        .andExpect(jsonPath("$.claims[1].claimText").value("두 번째 claim — sort_order=1"))
        .andExpect(jsonPath("$.claims[2].claimText").value("세 번째 claim — sort_order=2"));
  }

  @Test
  @DisplayName("VerificationResult 있는 claim — verdict 단언")
  void findVerification_claimWithResult_verdictPresent() throws Exception {
    // 회귀 시뮬 B 전제: VerificationResult 1건 이상 존재 + claims[0].verdict 단언
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claims[0].verdict").value("SUPPORTED"))
        .andExpect(jsonPath("$.claims[0].score").value(70))
        .andExpect(jsonPath("$.claims[0].truthLabel").value("MOSTLY_FACT"))
        // RC-06: SCORABLE(tier3Reason null) claim은 claimScoreStatus=null (truthLabel과 상호 배타)
        .andExpect(jsonPath("$.claims[0].claimScoreStatus").value(nullValue()));
  }

  @Test
  @DisplayName("VerificationResult 없는 claim — verdict/truthLabel null 단언")
  void findVerification_claimWithoutResult_verdictNull() throws Exception {
    // claim1, claim2는 VerificationResult 없음 — 미검증 claim
    // Jackson 기본 설정: null 필드는 "null"로 직렬화(NON_NULL 미설정) → value(nullValue()) 사용
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claims[1].verdict").value(nullValue()))
        .andExpect(jsonPath("$.claims[1].truthLabel").value(nullValue()))
        .andExpect(jsonPath("$.claims[2].verdict").value(nullValue()));
  }

  // ── 2. 404 처리 ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("GET /api/v1/articles/{id}/verification — 미존재 id → 404 + envelope")
  void findVerification_notFoundId_returns404() throws Exception {
    UUID unknownId = UUID.randomUUID();

    mockMvc
        .perform(get("/api/v1/articles/" + unknownId + "/verification"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404));
  }

  // ── 3. sourceTransparency null (66a 계약) ─────────────────────────────────

  @Test
  @DisplayName("sourceTransparency=null 단언 — 66a 계약, 66b에서 채움")
  void findVerification_sourceTransparency_isNull() throws Exception {
    // 66a 계약: sourceTransparency=null (Jackson 기본: null 필드는 "null"로 직렬화)
    mockMvc
        .perform(get("/api/v1/articles/" + savedArticle.getId() + "/verification"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceTransparency").value(nullValue()));
  }
}
