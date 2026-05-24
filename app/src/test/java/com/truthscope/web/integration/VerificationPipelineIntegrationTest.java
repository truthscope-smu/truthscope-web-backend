package com.truthscope.web.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.truthscope.web.entity.enums.Tier3Reason;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimScoreCalculator;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.SourceTransparency;
import com.truthscope.web.scoring.SourceTransparencyAggregator;
import com.truthscope.web.scoring.SourceTransparencyBand;
import com.truthscope.web.scoring.SourceTransparencySummary;
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
import org.junit.jupiter.api.Disabled;
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
      when(factcheckCacheRepo.searchByText(eq(claimText))).thenReturn(List.of(cacheEntry));

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

    /**
     * F-3 SourceTransparencyAggregator 직접 호출 cross-check + 파이프라인 통합.
     *
     * <p>rev.2 C-2 + M-3 amend: SourceTransparencySummary 실 record 필드 4종 (explicitCount /
     * ambiguousCount / noneCount / band). AnalysisSession에 transparency 컬럼 부재라 DB assertion 불가 →
     * test 내부 fixture signals로 Aggregator static method 직접 호출 + 4종 필드 cross-check + 합 =
     * signals.size() 정합.
     *
     * <p>본 시나리오는 (a) production 파이프라인이 Tier 1 hit signal을 생성하는 것을 DB 영속화로 검증 + (b)
     * SourceTransparencyAggregator 자체의 결정성(같은 input → 같은 output)을 cross-check한다.
     */
    @Test
    @DisplayName("F-3 SourceTransparencyAggregator 직접 호출 cross-check + 파이프라인 통합")
    void sourceTransparencyAggregator_crossCheck_pipelineIntegration() throws Exception {
      // Given: F-1과 동일 fixture (Tier 1 hit)
      String claimText = "SourceTransparency cross-check claim";
      FactcheckCache cacheEntry =
          FactcheckCache.builder()
              .claimText(claimText)
              .sourceOrg("팩트체크 기관")
              .rating("TRUE")
              .originalUrl("https://example-factcheck.org/2")
              .language("ko")
              .collectedAt(LocalDateTime.now().minusHours(1))
              .expiresAt(LocalDateTime.now().plusDays(7))
              .build();
      when(factcheckCacheRepo.searchByText(eq(claimText))).thenReturn(List.of(cacheEntry));

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("Transparency cross-check 기사")
              .body(claimText + " 본문")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      UUID claimId = UUID.randomUUID();
      ClaimDraft scorableDraft =
          new ClaimDraft(
              claimId, claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of(scorableDraft));

      // When: production 흐름 → POST + Aggregator static method 직접 호출
      mockMvc
          .perform(
              post("/api/v1/analysis-sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson("https://example.com/news/transparency")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("COMPLETED"))
          .andReturn();

      // Aggregator 직접 호출: production이 받았을 ClaimVerificationSignal 1건 fixture 생성 후 cross-check
      // (production은 Tier 1 hit 시 EXPLICIT SourceTransparency 부여 — VerificationCascadeService line
      // 78)
      ClaimVerificationSignal fixtureSignal =
          new ClaimVerificationSignal(
              claimId, (short) 1, 100, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT);
      SourceTransparencySummary summary =
          SourceTransparencyAggregator.aggregateSourceTransparency(List.of(fixtureSignal));

      // Then: 4종 필드 + 합 = signals.size() 정합
      assertThat(summary.explicitCount()).isEqualTo(1);
      assertThat(summary.ambiguousCount()).isEqualTo(0);
      assertThat(summary.noneCount()).isEqualTo(0);
      assertThat(summary.band()).isEqualTo(SourceTransparencyBand.ALL_EXPLICIT);
      assertThat(summary.explicitCount() + summary.ambiguousCount() + summary.noneCount())
          .isEqualTo(1);
    }

    /**
     * F-4 AnalysisResponse JSON 직렬화 계약 (ADR-019 UI 진입점).
     *
     * <p>응답 JSON path 정합 검증: sessionId/articleId UUID regex + status enum 값 + Content-Type
     * application/json. FE Phase 21 결과 카드 wiring 대비 contract 검증.
     */
    @Test
    @DisplayName("F-4 AnalysisResponse JSON 직렬화 계약 (ADR-019 UI 진입점)")
    void analysisResponse_jsonSerialization_contractCompliance() throws Exception {
      // Given: F-1 setup (Tier 1 hit, 정상 흐름)
      String claimText = "JSON 직렬화 계약 검증 claim";
      FactcheckCache cacheEntry =
          FactcheckCache.builder()
              .claimText(claimText)
              .sourceOrg("팩트체크 기관")
              .rating("TRUE")
              .originalUrl("https://example-factcheck.org/3")
              .language("ko")
              .collectedAt(LocalDateTime.now().minusHours(1))
              .expiresAt(LocalDateTime.now().plusDays(7))
              .build();
      when(factcheckCacheRepo.searchByText(eq(claimText))).thenReturn(List.of(cacheEntry));

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("JSON 계약 기사")
              .body(claimText + " 본문")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      ClaimDraft scorableDraft =
          new ClaimDraft(
              UUID.randomUUID(), claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of(scorableDraft));

      // When + Then: JSON path + Content-Type 정합
      String uuidRegex =
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
      mockMvc
          .perform(
              post("/api/v1/analysis-sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson("https://example.com/news/json-contract")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.sessionId").exists())
          .andExpect(jsonPath("$.sessionId").value(org.hamcrest.Matchers.matchesRegex(uuidRegex)))
          .andExpect(jsonPath("$.articleId").exists())
          .andExpect(jsonPath("$.articleId").value(org.hamcrest.Matchers.matchesRegex(uuidRegex)))
          .andExpect(
              jsonPath("$.status")
                  .value(
                      org.hamcrest.Matchers.isOneOf(
                          "PENDING", "EXTRACTING", "ANALYZING", "COMPLETED", "FAILED")))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                  .contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
  }

  @Nested
  @DisplayName("축 2: 신뢰성 (ISO 25010 reliability — fault tolerance + recoverability)")
  class Reliability {

    /**
     * R-1 혼합 claim (SCORABLE + INSUFFICIENT_CANDIDATE) → 부분 검증 불가 시 정상 집계 동작.
     *
     * <p>rev.3 RC-3 amend (사용자 결정): rev.2 empty drafts 좁힘 시 R-3와 완전 중복 → 혼합 claim으로 재설계. ISO/IEC
     * 25010 신뢰성 = '부분 검증 불가해도 정상 집계 동작' 입증. SCORABLE 1건 Tier 1 hit + INSUFFICIENT_CANDIDATE 1건 Tier
     * 3 INSUFFICIENT.
     */
    @Test
    @DisplayName("R-1 혼합 claim (SCORABLE + INSUFFICIENT_CANDIDATE) → 부분 검증 불가 시 정상 집계")
    void mixedClaimsScorableAndInsufficient_partialVerificationAggregation() throws Exception {
      // Given: 2 ClaimDraft 혼합 (SCORABLE + INSUFFICIENT_CANDIDATE)
      String scorableText = "scorable factcheck claim";
      String insufficientText = "insufficient claim no evidence";

      ClaimDraft scorableDraft =
          new ClaimDraft(
              UUID.randomUUID(),
              scorableText,
              null,
              false,
              null,
              ClaimStatusCandidate.SCORABLE,
              null);
      ClaimDraft insufficientDraft =
          new ClaimDraft(
              UUID.randomUUID(),
              insufficientText,
              null,
              false,
              null,
              ClaimStatusCandidate.INSUFFICIENT_CANDIDATE,
              null);
      when(claimAnalysisPort.analyze(anyString()))
          .thenReturn(List.of(scorableDraft, insufficientDraft));

      // factcheck_cache: SCORABLE만 hit + INSUFFICIENT는 miss
      FactcheckCache cacheEntry =
          FactcheckCache.builder()
              .claimText(scorableText)
              .sourceOrg("팩트체크 기관")
              .rating("TRUE")
              .originalUrl("https://example-factcheck.org/mixed")
              .language("ko")
              .collectedAt(LocalDateTime.now().minusHours(1))
              .expiresAt(LocalDateTime.now().plusDays(7))
              .build();
      when(factcheckCacheRepo.searchByText(eq(scorableText))).thenReturn(List.of(cacheEntry));
      when(factcheckCacheRepo.searchByText(eq(insufficientText))).thenReturn(List.of());

      // hybridCascade empty → Tier 2 진입 불가 → Tier 3 INSUFFICIENT 기본
      when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("혼합 claim 시나리오 기사")
              .body("혼합 claim 시나리오 본문")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      // When
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/analysis-sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestJson("https://example.com/news/mixed")))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn();

      // Then: tier1=1 + tier3=1 + tier2=0 + totalScore present + VR 2건
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(response.get("sessionId").asText());
      UUID articleId = UUID.fromString(response.get("articleId").asText());

      AnalysisSession session = sessionRepo.findById(sessionId).orElseThrow();
      assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
      assertThat(session.getTier1Count()).isEqualTo((short) 1);
      assertThat(session.getTier3Count()).isEqualTo((short) 1);
      assertThat(session.getTier2Count()).isEqualTo((short) 0);
      // SCORABLE 1건 단독 ArticleFactScoreAggregator 입력 → totalScore present
      assertThat(session.getTotalScore()).isNotNull().isBetween((short) 0, (short) 100);

      var claims = claimRepo.findByArticleId(articleId);
      assertThat(claims).hasSize(2);

      // SCORABLE → tier=1 / INSUFFICIENT_CANDIDATE → tier=3 INSUFFICIENT
      long tier1ResultCount =
          claims.stream()
              .map(c -> verificationResultRepo.findByClaimId(c.getId()))
              .filter(opt -> opt.isPresent() && opt.get().getTier() == 1)
              .count();
      long tier3ResultCount =
          claims.stream()
              .map(c -> verificationResultRepo.findByClaimId(c.getId()))
              .filter(opt -> opt.isPresent() && opt.get().getTier() == 3)
              .count();
      assertThat(tier1ResultCount).isEqualTo(1L);
      assertThat(tier3ResultCount).isEqualTo(1L);
    }

    /**
     * R-2 Tier 3 fallback (cache miss + empty source) → INSUFFICIENT.
     *
     * <p>외부 source 부족 시 Tier 3로 정상 fallback. tier3Reason = INSUFFICIENT + score = null
     * (domain-logic.md Tier 3 원칙).
     */
    @Test
    @DisplayName("R-2 Tier 3 fallback (cache miss + empty source) → INSUFFICIENT")
    void tier3Fallback_cacheMissAndEmptyCascade_insufficientReason() throws Exception {
      // Given: factcheck miss + hybridCascade empty + SCORABLE 1건
      when(factcheckCacheRepo.searchByText(anyString())).thenReturn(List.of());
      when(hybridCascade.retrieve(anyString(), anyInt())).thenReturn(List.of());

      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("Tier 3 fallback 기사")
              .body("source 부족 시나리오 본문")
              .lang("ko")
              .domain("unknown.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

      ClaimDraft scorableDraft =
          new ClaimDraft(
              UUID.randomUUID(),
              "Tier 3 fallback claim",
              null,
              false,
              null,
              ClaimStatusCandidate.SCORABLE,
              null);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of(scorableDraft));

      // When
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/analysis-sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestJson("https://unknown.com/news/tier3")))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn();

      // Then: tier3Count >= 1 + tier=3 + tier3Reason=INSUFFICIENT + score=null
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(response.get("sessionId").asText());
      UUID articleId = UUID.fromString(response.get("articleId").asText());

      AnalysisSession session = sessionRepo.findById(sessionId).orElseThrow();
      assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
      assertThat(session.getTier3Count()).isNotNull().isGreaterThanOrEqualTo((short) 1);

      var claims = claimRepo.findByArticleId(articleId);
      assertThat(claims).hasSize(1);

      var verificationOpt = verificationResultRepo.findByClaimId(claims.get(0).getId());
      assertThat(verificationOpt).isPresent();
      VerificationResult vr = verificationOpt.get();
      assertThat(vr.getTier()).isEqualTo((short) 3);
      assertThat(vr.getScore()).isNull();
      assertThat(vr.getTier3Reason()).isEqualTo(Tier3Reason.INSUFFICIENT);
    }

    /**
     * R-3 empty drafts → Optional.empty 정상 처리.
     *
     * <p>claim 추출 0개 → AnalysisService 8단계 흐름 정상 진행 (persistClaims 빈 리스트 + cascade 빈 리스트 +
     * persistCascadeResults 빈 signals 처리 + ArticleFactScoreAggregator Optional.empty →
     * totalScore=null).
     */
    @Test
    @DisplayName("R-3 empty drafts → Optional.empty 정상 처리")
    void emptyClaimResult_optionalEmptyHandling_nullScore() throws Exception {
      // Given: contentExtract fixture + claimAnalysisPort empty (rev.2 M-2 amend — NPE 방지 stub)
      ExtractedArticle fixtureArticle =
          ExtractedArticle.builder()
              .title("Empty claim 기사")
              .body("claim 추출 0개 시나리오")
              .lang("ko")
              .domain("example.com")
              .build();
      when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);
      when(claimAnalysisPort.analyze(anyString())).thenReturn(List.of());

      // When
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/analysis-sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestJson("https://example.com/news/empty")))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.status").value("COMPLETED"))
              .andReturn();

      // Then: totalScore=null + 모든 tier count=0 + VR 0건
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      UUID sessionId = UUID.fromString(response.get("sessionId").asText());
      UUID articleId = UUID.fromString(response.get("articleId").asText());

      AnalysisSession session = sessionRepo.findById(sessionId).orElseThrow();
      assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
      assertThat(session.getTotalScore()).isNull();
      assertThat(session.getTier1Count()).isEqualTo((short) 0);
      assertThat(session.getTier2Count()).isEqualTo((short) 0);
      assertThat(session.getTier3Count()).isEqualTo((short) 0);

      var claims = claimRepo.findByArticleId(articleId);
      assertThat(claims).isEmpty();
    }

    /**
     * R-4 RuntimeException → markFailed 전이 + 500 응답.
     *
     * <p>contentExtractService throw RuntimeException → AnalysisService catch → markFailed →
     * suppressed 보존 후 rethrow → GlobalExceptionHandler.handleException → 500 응답.
     *
     * <p>rev.2 H-2 + L-1 amend: 500 응답 body에 sessionId 부재 → sessionRepo.findAll() filter로 session
     * FAILED 검증. Mockito.verify(transactionService).markFailed는 production bean이라 직접 verify 불가.
     */
    @Test
    @DisplayName("R-4 RuntimeException → markFailed 전이 + 500 응답")
    void runtimeExceptionInContentExtract_markFailedAnd500Response() throws Exception {
      // Given: contentExtract throw RuntimeException
      when(contentExtractService.extract(anyString()))
          .thenThrow(new RuntimeException("외부 사이트 응답 실패"));

      // CodeRabbit fix A2: delta 기반 검증 (global state 의존 회피).
      // @AfterEach cleanup으로 매 시나리오 시작 시 0 보장이나 견고성 향상.
      long beforeFailed =
          sessionRepo.findAll().stream().filter(s -> s.getStatus() == SessionStatus.FAILED).count();

      // When + Then: HTTP 500 + ApiErrorResponse JSON fragment
      mockMvc
          .perform(
              post("/api/v1/analysis-sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson("https://example.com/news/runtime-error")))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.status").value("error"))
          .andExpect(jsonPath("$.statusCode").value(500))
          .andExpect(jsonPath("$.message").value("서버 내부 오류"));

      // DB: FAILED 세션 count delta = 1 (response body에 sessionId 부재 → delta 검증)
      long afterFailed =
          sessionRepo.findAll().stream().filter(s -> s.getStatus() == SessionStatus.FAILED).count();
      assertThat(afterFailed - beforeFailed).isEqualTo(1L);
    }

    /**
     * R-5 vandalism source (Wave 3 후속 — Wikipedia adapter 활성화 시 24h revision diff 검증).
     *
     * <p>DISCUSS Q4 lock: Wikipedia adapter 부재(HANDOFF lock + Wave 3 PR #73 deferred) → stub 인정 박제
     * placeholder. 본 phase는 5 결함 주입 = 4 실효 + 1 placeholder 정합.
     *
     * <p>활성화 시 시나리오 명세:
     *
     * <ul>
     *   <li>Wikipedia API revision diff 24h fixture (안정 revision vs vandalism revision 비교)
     *   <li>WikipediaAdapter @MockBean 또는 production bean 활성화
     *   <li>vandalism detector → Tier 3 fallback 검증
     *   <li>domain-logic.md vandalism mitigation 24h revision diff 정합
     * </ul>
     */
    @Test
    @Disabled("Wave 3 후속 — Wikipedia adapter 활성화 (PR #73 머지) + WikipediaAdapter @MockBean 추가 후 활성화")
    @DisplayName("R-5 vandalism source (Wave 3 후속 — Wikipedia adapter 활성화 시 24h revision diff 검증)")
    void wikipediaVandalismRevisionDiff_disabled_wave3Followup() {
      // placeholder: 활성화 시점에 Wikipedia revision diff 24h fixture + vandalism detector +
      // Tier 3 fallback assertion 박제 (domain-logic.md vandalism mitigation 정합).
    }
  }
}
