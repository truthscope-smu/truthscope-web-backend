package com.truthscope.web.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.FactcheckCache;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreCalculator;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.service.ContentExtractService;
import com.truthscope.web.service.verification.HybridCascadeService;
import com.truthscope.web.url.UrlValidator;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE #66 Sprint 4 S4-03 통합 테스트 2축 (기능 적합성 + 신뢰성) D-F 정합.
 *
 * <p>3차 면담 (2026-05-21) D-F 결정 + amendment doc sprint-4-amendment-2026-05-23 정합. ISO/IEC 25010
 * functional suitability + reliability 2축으로 6/8 SW설계 최종 제출 산출물 정량 증거 박제.
 *
 * <p>진입점 (DISCUSS Q2 lock + codex 5.5 thread 019e5947 cross-review verdict): C-MOCK
 * {@code @SpringBootTest(MOCK) + @AutoConfigureMockMvc + Singleton PostgreSQLContainer +
 * 7종 @MockBean}. Wave 2 VerificationCascadeIntegrationTest 패턴 정합 + HTTP layer 확장으로 ISO 25010 2축 입증력
 * 강화.
 *
 * <p>시나리오 매트릭스 9건:
 *
 * <ul>
 *   <li>축 1 기능 적합성 4건 — F-1 Tier 1 hit + Phase 55 통합 / F-2 Tier 2 hit / F-3
 *       SourceTransparencyAggregator cross-check / F-4 AnalysisResponse JSON 직렬화
 *   <li>축 2 신뢰성 5건 — R-1 혼합 claim (SCORABLE + INSUFFICIENT_CANDIDATE) / R-2 Tier 3 fallback / R-3
 *       empty drafts / R-4 RuntimeException 500 응답 / R-5 vandalism @Disabled placeholder
 * </ul>
 *
 * <p>@MockBean 7종 (PLAN rev.3 RC-1 + RC-2 amend):
 *
 * <ul>
 *   <li>ContentExtractService — 외부 HTTP Jsoup 차단 + fixture ExtractedArticle 반환
 *   <li>ClaimAnalysisPort — Gemini 실 HTTP 차단 + fixture ClaimDraft 반환
 *   <li>FactcheckCacheRepository — Supabase tsvector search_vector Flyway 외부 우회
 *   <li>HybridCascadeService — Wave 2 stub 한계 회피 + Tier 2 fixture snapshot 반환
 *   <li>@Qualifier("policyScorer") ClaimScoreCalculator — Tier 2 SCORABLE 점수 결정적 제어
 *   <li>@Qualifier("stanceScorer") ClaimScoreCalculator — Tier 2 fallback scorer 제어
 *   <li>UrlValidator — 실 HTTP HEAD 요청 차단 (PLAN rev.3 RC-2 amend, UrlValidator.validate line 99)
 * </ul>
 *
 * <p>Singleton Testcontainers + @ServiceConnection 패턴 (V6MigrationTest 정본). AbstractIntegrationTest
 * 상속 금지 (HANDOFF lock + be21 정합).
 *
 * <p>VerificationTrace 14컬럼 assertion 금지 (Wave 2 µ2.4 deferred 박제). AnalysisSession 컬럼 +
 * VerificationResult 컬럼만 검증.
 *
 * <p>@MockBean Spring Boot 3.4 deprecated — @MockitoBean 전환은 별 phase 이슈 (Sprint 5 backlog 후보).
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("production")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "truthscope.gemini.api-key=test-key",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@DisplayName("BE #66 통합 테스트 2축 — 기능 적합성 + 신뢰성 (ISO/IEC 25010 정합)")
class VerificationPipelineIntegrationTest {

  // Singleton Testcontainers + @ServiceConnection — V6MigrationTest 정본 정합
  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  // ── @MockBean 7종 (PLAN rev.3 RC-1 실 bean 이름 + RC-2 UrlValidator HTTP 차단) ─────

  @MockBean ContentExtractService contentExtractService;

  @MockBean ClaimAnalysisPort claimAnalysisPort;

  @MockBean FactcheckCacheRepository factcheckCacheRepo;

  @MockBean HybridCascadeService hybridCascade;

  @MockBean
  @Qualifier("policyScorer")
  ClaimScoreCalculator policyScorer;

  @MockBean
  @Qualifier("stanceScorer")
  ClaimScoreCalculator stanceScorer;

  @MockBean UrlValidator urlValidator;

  // ── @Autowired (HTTP + DB + CB + JSON) ─────────────────────────────────

  @Autowired MockMvc mockMvc;

  @Autowired ObjectMapper objectMapper;

  @Autowired AnalysisSessionRepository sessionRepo;

  @Autowired VerificationResultRepository verificationResultRepo;

  @Autowired ClaimRepository claimRepo;

  @Autowired ArticleRepository articleRepo;

  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

  @AfterEach
  void tearDown() {
    // FK 의존 순서 cleanup (PLAN rev.3 RC-4 cross-reference)
    // Wave 2 정본 VerificationCascadeIntegrationTest line 109-118은 articleRepo 의도적 생략
    // (주석: "session.delete로 orphan 제거"). 본 phase는 cascade 미설정 + 명시적
    // articleRepo.deleteAll() 추가로 FK orphan 위험 차단.
    verificationResultRepo.deleteAll();
    claimRepo.deleteAll();
    articleRepo.deleteAll();
    sessionRepo.deleteAll();

    // @MockBean 7종 stub leak 차단
    Mockito.reset(
        contentExtractService,
        claimAnalysisPort,
        factcheckCacheRepo,
        hybridCascade,
        policyScorer,
        stanceScorer,
        urlValidator);

    // CircuitBreaker reset (R-1 외 시나리오 보호)
    circuitBreakerRegistry.circuitBreaker("gemini").reset();
  }

  /** mockMvc POST 요청 JSON 생성 헬퍼 (PLAN 1장 결정 정합). */
  private String requestJson(String url) throws Exception {
    return objectMapper.writeValueAsString(new AnalysisRequest(url));
  }

  // ── @Nested 2 inner class (시나리오 본문은 Wave 2/3에서 추가) ─────────────

  @Nested
  @DisplayName("축 1: 기능 적합성 (ISO 25010 functional suitability)")
  class FunctionalSuitability {

    /**
     * F-1 Tier 1 hit + Phase 55 4 함수 통합 + ADR-019 UI 정합.
     *
     * <p>factcheck_cache 매칭 시 cascade가 Tier 1 SCORABLE 100점 signal 반환 → Phase 55 4함수가 totalScore +
     * coverage + transparency 집계 → AnalysisSession.completeCascade 영속화. 응답 + DB state 양방 검증.
     */
    @Test
    @DisplayName("F-1 Tier 1 hit + Phase 55 4 함수 통합 + ADR-019 UI 정합")
    void tier1Hit_phase55Integration_responseAndDbVerified() throws Exception {
      // Given: factcheck_cache 매칭 1건 + ExtractedArticle fixture + SCORABLE 1건
      String claimText = "정부 정책 예산 증액 발표";
      FactcheckCache cacheEntry =
          FactcheckCache.builder()
              .claimText(claimText)
              .sourceOrg("팩트체크 기관")
              .rating("TRUE")
              .originalUrl("https://example-factcheck.org/1")
              .language("ko")
              .collectedAt(LocalDateTime.now().minusHours(1))
              .expiresAt(LocalDateTime.now().plusDays(7))
              .build();
      when(factcheckCacheRepo.searchByText(anyString())).thenReturn(List.of(cacheEntry));

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("정책 예산 기사")
              .body(claimText + " 추가 본문 내용")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      ClaimDraft scorableDraft =
          new ClaimDraft(
              UUID.randomUUID(), claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of(scorableDraft));

      // When: POST /api/v1/analysis-sessions
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/analysis-sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestJson("https://example.com/news/tier1")))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.sessionId").exists())
              .andExpect(jsonPath("$.articleId").exists())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn();

      // Then: DB state 검증
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(response.get("sessionId").asText());
      UUID articleId = UUID.fromString(response.get("articleId").asText());

      AnalysisSession session = sessionRepo.findById(sessionId).orElseThrow();
      assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
      assertThat(session.getTier1Count()).isNotNull().isGreaterThanOrEqualTo((short) 1);
      assertThat(session.getTotalScore()).isNotNull().isBetween((short) 0, (short) 100);
      assertThat(session.getCoverage()).isNotNull();

      var claims = claimRepo.findByArticleId(articleId);
      assertThat(claims).hasSize(1);

      var verificationOpt = verificationResultRepo.findByClaimId(claims.get(0).getId());
      assertThat(verificationOpt).isPresent();
      VerificationResult vr = verificationOpt.get();
      assertThat(vr.getTier()).isEqualTo((short) 1);
      assertThat(vr.getScore()).isNotNull().isBetween((short) 0, (short) 100);
    }

    /**
     * F-2 Tier 2 hit (HybridCascade + urlValidator + policyScorer stub) + 결과 카드 정합.
     *
     * <p>rev.3 RC-1 amend: @Qualifier("policyScorer") 실 bean 이름 정합. rev.3 RC-2 amend:
     * UrlValidator @MockBean으로 실 HTTP HEAD 차단.
     *
     * <p>cascade 흐름: factcheck miss → hybridCascade 3 snapshot → urlValidator true 통과 →
     * validSnapshots size 3 >= threshold 3 → policyScorer Optional.of(70) → Tier 2 SCORABLE 70점.
     */
    @Test
    @DisplayName("F-2 Tier 2 hit (HybridCascade + urlValidator + policyScorer stub) + 결과 카드 정합")
    void tier2Hit_scorerStub_responseAndDbVerified() throws Exception {
      // Given: Tier 1 miss + HybridCascade 3 snapshot + urlValidator true + policyScorer
      // Optional.of(70)
      when(factcheckCacheRepo.searchByText(anyString())).thenReturn(List.of());

      List<EvidenceSnapshot> snapshots =
          List.of(
              new EvidenceSnapshot(
                  "https://valid-source.com/article-1",
                  "공식 출처 1",
                  "기사 제목 1",
                  "SUPPORTED",
                  Map.of()),
              new EvidenceSnapshot(
                  "https://valid-source.com/article-2",
                  "공식 출처 2",
                  "기사 제목 2",
                  "SUPPORTED",
                  Map.of()),
              new EvidenceSnapshot(
                  "https://valid-source.com/article-3",
                  "공식 출처 3",
                  "기사 제목 3",
                  "SUPPORTED",
                  Map.of()));
      when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(snapshots);

      // rev.3 RC-2 amend: UrlValidator 실 HTTP HEAD 차단
      when(urlValidator.validate(anyString())).thenReturn(true);

      // rev.3 RC-1 amend: policyScorer 실 bean 이름 + Optional.of(70) 직접 제어
      when(policyScorer.calculate(any(), anyList(), any())).thenReturn(Optional.of(70));

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("Tier 2 시나리오 기사")
              .body("Tier 2 검증 시나리오 본문")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      ClaimDraft scorableDraft =
          new ClaimDraft(
              UUID.randomUUID(),
              "Tier 2 검증 claim",
              null,
              false,
              null,
              ClaimStatusCandidate.SCORABLE,
              null);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of(scorableDraft));

      // When: POST
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/analysis-sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestJson("https://example.com/news/tier2")))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn();

      // Then: DB tier2Count >= 1 + tier1Count = 0 + totalScore + Tier 2 disclaimer
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(response.get("sessionId").asText());
      UUID articleId = UUID.fromString(response.get("articleId").asText());

      AnalysisSession session = sessionRepo.findById(sessionId).orElseThrow();
      assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
      assertThat(session.getTier2Count()).isNotNull().isGreaterThanOrEqualTo((short) 1);
      assertThat(session.getTier1Count()).isEqualTo((short) 0);
      assertThat(session.getTotalScore()).isNotNull().isBetween((short) 0, (short) 100);

      var claims = claimRepo.findByArticleId(articleId);
      assertThat(claims).hasSize(1);

      var verificationOpt = verificationResultRepo.findByClaimId(claims.get(0).getId());
      assertThat(verificationOpt).isPresent();
      VerificationResult vr = verificationOpt.get();
      assertThat(vr.getTier()).isEqualTo((short) 2);
      assertThat(vr.getDisclaimer()).isEqualTo("AI 분석이며 기관 검증이 아닙니다. 참고 용도로만 활용하세요.");
    }
  }

  @Nested
  @DisplayName("축 2: 신뢰성 (ISO 25010 reliability — fault tolerance + recoverability)")
  class Reliability {
    // Wave 3 추가 예정: R-1 ~ R-5 (5 시나리오)
  }
}
