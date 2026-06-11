package com.truthscope.web.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.truthscope.web.dto.request.AnalysisRequest;
import com.truthscope.web.dto.response.ExtractedArticle;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.FactcheckCache;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.FactcheckCacheRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.scoring.ClaimAnalysisPort;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.service.AnalysisService;
import com.truthscope.web.service.ContentExtractService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 Verification Cascade 통합 테스트 (µ2.5 T2-11).
 *
 * <p>PLAN §11-2 lines 1318-1319 정합. Flyway V1..V6 적용 후 분석 파이프라인이 DB 에 cascade 결과를 정상 영속화하는지 검증한다.
 *
 * <p>스코프 결정 (Tier 1 + Tier 3 fallback):
 *
 * <ul>
 *   <li>Scenario A: Tier 1 hit — factcheck_cache 에 매칭 항목 seed → analyze 결과 VerificationResult
 *       tier=1 + AnalysisSession.completeCascade 필드 검증
 *   <li>Scenario B: Tier 3 fallback — factcheck_cache 비움 + ClaimAnalysisPort stub 반환 + cascade
 *       source 부족 → INSUFFICIENT + session tier3Count 검증
 * </ul>
 *
 * <p>모킹 전략:
 *
 * <ul>
 *   <li>{@link ContentExtractService}: 외부 HTTP(Jsoup + SSRF 방어) 차단 목적으로 @MockBean. fixture
 *       ExtractedArticle 반환.
 *   <li>{@link ClaimAnalysisPort}: @MockBean. production profile 의 ClaimAnalysisService 가 Gemini 실제
 *       HTTP 호출을 시도하므로 차단. fixture ClaimDraft 반환.
 * </ul>
 *
 * <p>VerificationTrace 14-column assertion:
 *
 * <pre>
 * TODO µ2.5 amend: VerificationTrace 14 named columns assert — depends on Gemini cascade wiring
 *   (deferred from T2-10). T2-11 first pass 에서는 VerificationTrace 영속화가 미완성(skeleton)이므로 skip.
 * </pre>
 *
 * <p>Singleton Testcontainers + @ServiceConnection 패턴 (V6MigrationTest 정합). AbstractIntegrationTest
 * 상속 금지 — 그 base 는 create-drop + flyway 비활성이라 drift 검증 불가.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("production")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "truthscope.gemini.api-key=test-key",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration",
      "spring.main.allow-bean-definition-overriding=true",
      "truthscope.async.enabled=false"
    })
@Import(com.truthscope.web.support.SyncAnalysisExecutorConfig.class)
@DisplayName("Wave 2 Verification Cascade 통합 테스트 (µ2.5 T2-11)")
class VerificationCascadeIntegrationTest {

  // Singleton Testcontainers 패턴 — V6MigrationTest 정합
  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  // 외부 HTTP 차단 목적 @MockBean — ContentExtractService 는 SSRF 방어 + Apache HC5 실제 호출
  @MockBean ContentExtractService contentExtractService;

  // Gemini 실제 HTTP 호출 차단 목적 @MockBean — ClaimAnalysisPort (production = ClaimAnalysisService)
  @MockBean ClaimAnalysisPort claimAnalysisPort;

  // factcheck_cache 의 search_vector 컬럼은 Supabase 전용 tsvector(트리거 관리) — Flyway 외부.
  // Testcontainers PostgreSQL에는 컬럼 부재 → FactcheckCacheRepository.searchByText native SQL 실패.
  // @MockBean 으로 우회하여 Tier 1 cache 응답을 결정적으로 제어.
  @MockBean FactcheckCacheRepository factcheckCacheRepo;

  @Autowired AnalysisService analysisService;
  @Autowired AnalysisSessionRepository sessionRepo;
  @Autowired VerificationResultRepository verificationResultRepo;
  @Autowired ClaimRepository claimRepo;

  @AfterEach
  void tearDown() {
    // DB 독립성 보장 — 각 테스트 후 전체 cleanup
    // 외래키 의존 순서: verification_results → claims → articles → analysis_sessions → factcheck_cache
    verificationResultRepo.deleteAll();
    claimRepo.deleteAll();
    // articles, analysis_sessions 은 cascade 또는 별도 레포 없이 session.delete 로 연쇄 처리되지 않으므로
    // JdbcTemplate 또는 EntityManager 직접 사용 없이 세션 레포 deleteAll 로 처리 (orphan 제거)
    sessionRepo.deleteAll();
    // factcheckCacheRepo는 @MockBean — deleteAll 호출 안 함 (실 DB 미사용)
  }

  // ── Scenario A: Tier 1 hit ──────────────────────────────────────────────

  /**
   * Tier 1 히트 시나리오: factcheck_cache 에 claim_text 매칭 항목을 seed 하고 전체 파이프라인을 실행한다.
   *
   * <p>FactcheckCache.searchByText 는 PostgreSQL plainto_tsquery 를 사용하므로 Testcontainers PostgreSQL
   * 실제 DB 위에서만 검증 가능하다.
   *
   * <p>단언:
   *
   * <ul>
   *   <li>AnalysisSession status = COMPLETED
   *   <li>AnalysisSession.tier1Count >= 1 (Tier 1 히트)
   *   <li>VerificationResult tier = 1, score in [0, 100]
   * </ul>
   */
  @Test
  @DisplayName("A-1 Tier 1 히트 — factcheck_cache 매칭 시 VerificationResult tier=1 + session COMPLETED")
  void tier1Hit_factcheckCacheSeedMatch_sessionCompletedAndResultTier1() {
    // Given: factcheckCacheRepo @MockBean — searchByText 호출 시 매칭 cache 1건 반환 (Tier 1 hit)
    String testClaimText = "government policy budget increase announcement";
    FactcheckCache cacheEntry =
        FactcheckCache.builder()
            .claimText(testClaimText)
            .sourceOrg("팩트체크 기관")
            .rating("TRUE")
            .originalUrl("https://example-factcheck.org/1")
            .language("en")
            .collectedAt(LocalDateTime.now().minusHours(1))
            .expiresAt(LocalDateTime.now().plusDays(7))
            .build();
    when(factcheckCacheRepo.searchByText(anyString())).thenReturn(List.of(cacheEntry));

    // Given: ContentExtractService stub — 외부 HTTP 차단 + fixture 반환
    UUID claimId = UUID.randomUUID();
    ExtractedArticle fixtureArticle =
        ExtractedArticle.builder()
            .title("정책 예산 기사")
            .body(testClaimText + " additional article body content for extraction")
            .lang("en")
            .domain("example.com")
            .build();
    when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

    // Given: ClaimAnalysisPort stub — Gemini 차단 + fixture ClaimDraft 반환
    // claimText 는 factcheck_cache seed 와 동일 → Tier 1 searchByText 히트 유도
    ClaimDraft fixtureDraft =
        new ClaimDraft(
            claimId, testClaimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
    when(claimAnalysisPort.analyze(anyString(), any())).thenReturn(List.of(fixtureDraft));

    // When
    var response =
        analysisService.analyze(new AnalysisRequest("https://example.com/news/tier1-test"));

    // Then: AnalysisSession COMPLETED 검증
    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo(SessionStatus.EXTRACTING.name());

    AnalysisSession session = sessionRepo.findById(response.getSessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    assertThat(session.getTier1Count()).isGreaterThanOrEqualTo((short) 1);

    // Then: VerificationResult tier=1 검증
    // claimId 기반 검색: persistClaims 가 DB UUID 를 재생성하므로 claim_text 기반 claimRepo 로 검색
    var claims = claimRepo.findByArticleId(response.getArticleId());
    assertThat(claims).hasSize(1);

    var resultOpt =
        verificationResultRepo.findByClaimIdAndSupersededAtIsNull(claims.get(0).getId());
    assertThat(resultOpt).isPresent();

    VerificationResult result = resultOpt.get();
    assertThat(result.getTier()).isEqualTo((short) 1);
    assertThat(result.getScore()).isNotNull().isBetween((short) 0, (short) 100);

    // TODO µ2.5 amend: VerificationTrace 14 named columns assert — depends on Gemini cascade
    //   wiring (deferred from T2-10). VerificationTrace 영속화는 µ2.5 HybridCascadeService + Gemini
    //   연동 후 구현 예정.
  }

  // ── Scenario B: Tier 3 fallback ────────────────────────────────────────

  /**
   * Tier 3 fallback 시나리오: factcheck_cache 가 비어 있고 HybridCascadeService 가 충분한 snapshot 을 반환하지 않는 상황
   * (실제 외부 API 호출 없음).
   *
   * <p>VerificationCascadeService 의 Tier 2 분기 조건 ({@code validSnapshots.size() >=
   * cascadePolicy.sourceCountThreshold()}) 이 불충족되면 Tier 3 INSUFFICIENT 로 떨어진다. Testcontainer DB 위에서
   * HybridCascadeService 가 실제 어댑터 없이 빈 snapshots 를 반환하면 cascade 는 Tier 3 경로를 밟는다.
   *
   * <p>단언:
   *
   * <ul>
   *   <li>AnalysisSession status = COMPLETED (Tier 3 도 정상 종료)
   *   <li>AnalysisSession.tier3Count >= 1
   *   <li>VerificationResult tier = 3
   * </ul>
   */
  @Test
  @DisplayName("B-1 Tier 3 fallback — cache miss + cascade source 부족 시 INSUFFICIENT + tier3Count")
  void tier3Fallback_cacheMissAndInsufficientSources_sessionCompletedAndResultTier3() {
    // Given: factcheckCacheRepo @MockBean — cache miss (empty list)
    when(factcheckCacheRepo.searchByText(anyString())).thenReturn(List.of());

    // Given: ContentExtractService stub
    ExtractedArticle fixtureArticle =
        ExtractedArticle.builder()
            .title("Tier 3 fallback 테스트 기사")
            .body("This is a body with content that will not match any factcheck cache entry.")
            .lang("en")
            .domain("unknown-news.com")
            .build();
    when(contentExtractService.extract(anyString())).thenReturn(fixtureArticle);

    // Given: ClaimAnalysisPort stub — SCORABLE draft 반환 (cascade 가 Tier 3 로 떨어지게)
    ClaimDraft fixtureDraft =
        new ClaimDraft(
            UUID.randomUUID(),
            "Some claim text with no matching source evidence available",
            null,
            false,
            null,
            ClaimStatusCandidate.SCORABLE,
            null);
    when(claimAnalysisPort.analyze(anyString(), any())).thenReturn(List.of(fixtureDraft));

    // When: analyze 실행 — HybridCascadeService 가 실제 어댑터 없이 빈 snapshots 반환 예상
    // (외부 API 키 없음 + test-key 환경 → 어댑터 호출 실패 or empty 반환 → Tier 3 경로)
    var response =
        analysisService.analyze(
            new AnalysisRequest("https://unknown-news.com/article/no-evidence"));

    // Then: session COMPLETED (Tier 3 도 정상 완료)
    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo(SessionStatus.EXTRACTING.name());

    AnalysisSession session = sessionRepo.findById(response.getSessionId()).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    // totalScore = null (Tier 3 는 score 없음 → ArticleFactScoreAggregator empty)
    assertThat(session.getTotalScore()).isNull();
    // tier3Count >= 1 (Tier 3 fallback 확인)
    assertThat(session.getTier3Count()).isGreaterThanOrEqualTo((short) 1);

    // Then: VerificationResult tier=3 검증
    var claims = claimRepo.findByArticleId(response.getArticleId());
    assertThat(claims).hasSize(1);

    var resultOpt =
        verificationResultRepo.findByClaimIdAndSupersededAtIsNull(claims.get(0).getId());
    assertThat(resultOpt).isPresent();
    assertThat(resultOpt.get().getTier()).isEqualTo((short) 3);
    // Tier 3 score 는 반드시 null (domain-logic.md Tier 3 원칙)
    assertThat(resultOpt.get().getScore()).isNull();

    // TODO µ2.5 amend: VerificationTrace 14 named columns assert — depends on Gemini cascade
    //   wiring (deferred from T2-10).
  }
}
