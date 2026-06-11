package com.truthscope.web.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import com.truthscope.web.entity.enums.ClaimImportance;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.entity.enums.SupersedeReason;
import com.truthscope.web.entity.enums.Verdict;
import com.truthscope.web.exception.ConflictException;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.exception.TooManyRequestsException;
import com.truthscope.web.factory.VerificationResultFactory;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.repository.VerificationResultRepository;
import com.truthscope.web.repository.VerifySourceRepository;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimScoreStatus;
import com.truthscope.web.scoring.ClaimVerificationSignal;
import com.truthscope.web.scoring.CoverageSummary;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.SourceTransparency;
import com.truthscope.web.service.ArticleVerificationService;
import com.truthscope.web.service.ReVerifyTransactionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 72 Wave5-G 통합 테스트 — ReVerify supersede 체인 + 회귀 핵심 시나리오.
 *
 * <p>I1 happy path: persistReverifyOutcome 호출 → 새 행 INSERT + 기존 행 supersede 마킹 + 세션 재집계. I2 4조건
 * 미충족: 기존 결과 보존 + last_confirmed_at 갱신. I3 쿨다운: validateAndGet 연속 호출 시 TooManyRequestsException. I4
 * superseded 행에 validateAndGet → ConflictException. I5 부재 id → NotFoundException. I6 회귀 핵심:
 * getVerification(articleId) 가 supersede 후 최신 결과 1건만 반환. I7 동시 2요청: advisory lock 으로 supersede 마킹
 * 1건만, 결과 행 2개, 예외 0.
 *
 * <p>Singleton Testcontainers + @ServiceConnection 패턴 (VerificationPipelineIntegrationTest 정본 정합).
 * production 프로파일 + Flyway V9 자동 적용으로 supersede 컬럼 + partial unique index 검증 겸함.
 *
 * <p>@SpringBootTest(NONE) + 서비스 직접 주입: HTTP layer bypass — cascade 외부 HTTP 없이 ClaimCascadeResult 를
 * 직접 구성하여 persistReverifyOutcome 호출.
 *
 * <p>쿨다운: 테스트 프로퍼티 cooldown=PT10M. 일반 fixture 는 verifiedAt=now()-2h 로 쿨다운 통과. I3 는 verifiedAt=now()
 * 로 validateAndGet 을 실제로 호출해 TooManyRequestsException(429) 가 발생함을 검증한다.
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
      "truthscope.async.enabled=false",
      "truthscope.reverify.cooldown=PT10M"
    })
@DisplayName("Phase 72 Wave5-G — ReVerify 통합 테스트 I1~I7")
class ReVerifyIntegrationTest {

  // Singleton Testcontainers + @ServiceConnection — VerificationPipelineIntegrationTest 정본 정합
  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @Autowired ReVerifyTransactionService reVerifyTransactionService;

  @Autowired ArticleVerificationService articleVerificationService;

  @Autowired VerificationResultRepository verificationResultRepository;

  @Autowired VerifySourceRepository verifySourceRepository;

  @Autowired ClaimRepository claimRepository;

  @Autowired ArticleRepository articleRepository;

  @Autowired AnalysisSessionRepository analysisSessionRepository;

  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    // superseded_by_result_id 자기참조 FK → 선행 NULL 처리 후 삭제
    jdbcTemplate.update("UPDATE verification_results SET superseded_by_result_id = NULL");
    verifySourceRepository.deleteAll();
    verificationResultRepository.deleteAll();
    claimRepository.deleteAll();
    articleRepository.deleteAll();
    analysisSessionRepository.deleteAll();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Fixture 헬퍼
  // ────────────────────────────────────────────────────────────────────────────

  /** 세션 ID 보관용 내부 레코드 — fixture 저장 후 세션 재조회에 사용. */
  private record FixtureResult(VerificationResult result, UUID sessionId) {}

  /**
   * 세션 + 기사 + claim + result(tier 1, score, supersededAt=null) + verify_source 1건을 DB 에 저장한다.
   *
   * <p>verifiedAt 을 충분히 과거(2시간 전)로 설정해 쿨다운(PT10M)을 항상 통과한다. I3 쿨다운 테스트는 verifiedAt=now() 를
   * saveFixtureWithVerifiedAt 으로 별도 지정한다.
   *
   * @param score 기존 판정 점수 (1~100). null 이면 Tier 3 fixture.
   * @return 저장된 FixtureResult (result + sessionId)
   */
  private FixtureResult saveFixture(Integer score) {
    return saveFixtureWithVerifiedAt(score, LocalDateTime.now().minusHours(2));
  }

  private FixtureResult saveFixtureWithVerifiedAt(Integer score, LocalDateTime verifiedAt) {
    AnalysisSession session =
        analysisSessionRepository.save(
            AnalysisSession.builder()
                .status(SessionStatus.COMPLETED)
                .requestedAt(LocalDateTime.now().minusMinutes(10))
                .completedAt(LocalDateTime.now().minusMinutes(9))
                .totalScore(score != null ? score.shortValue() : null)
                .coverage(
                    score != null
                        ? new CoverageSummary(1, 0, 0, 0, 0, 1, 0, 0)
                        : new CoverageSummary(0, 1, 1, 0, 0, 0, 0, 1))
                .tier1Count((short) (score != null ? 1 : 0))
                .tier2Count((short) 0)
                .tier3Count((short) (score != null ? 0 : 1))
                .build());

    Article article =
        Article.extract(
            "https://example.com/news/reverify-test-" + UUID.randomUUID(),
            "재검증 통합 테스트 기사",
            "본문 내용",
            "ko",
            "example.com");
    article.attachTo(session);
    article = articleRepository.save(article);

    Claim claim =
        claimRepository.save(
            Claim.builder()
                .article(article)
                .text("재검증 테스트 claim")
                .sortOrder((short) 1)
                .importance(ClaimImportance.MEDIUM)
                .build());

    short tier = score != null ? (short) 1 : (short) 3;
    ClaimVerificationSignal signal =
        new ClaimVerificationSignal(
            claim.getId(),
            tier,
            score,
            score != null ? ClaimScoreStatus.SCORABLE : ClaimScoreStatus.INSUFFICIENT,
            score != null ? SourceTransparency.EXPLICIT : SourceTransparency.NONE);

    VerificationResult result = VerificationResultFactory.buildResult(signal, claim, List.of());
    // verifiedAt 덮어쓰기: reflection 대신 Builder 재구성 (VerificationResultFactory 의 verifiedAt 은
    // LocalDateTime.now() 고정이므로 직접 빌드)
    result =
        verificationResultRepository.save(
            VerificationResult.builder()
                .claim(claim)
                .tier(tier)
                .score(score != null ? score.shortValue() : null)
                .verdict(score != null ? Verdict.SUPPORTED : Verdict.INSUFFICIENT)
                .tier3Reason(
                    score != null ? null : com.truthscope.web.entity.enums.Tier3Reason.INSUFFICIENT)
                .reason(score != null ? "Tier 1 팩트체크 기관 매칭 결과" : "Tier 3 검증 출처 부족")
                .disclaimer(null)
                .verifiedAt(verifiedAt)
                .originalResultId(null)
                .build());

    // verify_source 1건 저장 (oldUrls 비교용)
    if (score != null) {
      verifySourceRepository.save(
          VerifySource.builder()
              .result(result)
              .title("테스트 출처")
              .publisher("example.com")
              .url("https://source.example.com/article-1")
              .stance("supports")
              .build());
    }

    return new FixtureResult(result, session.getId());
  }

  /**
   * SCORE_DRIFT 를 유발하는 새 ClaimCascadeResult 를 생성한다.
   *
   * <p>기존 점수와 차이가 16 (임계값 15 초과) 이며 동일 라벨 구간(FACT)에 해당하는 점수를 사용한다: old=99, new=83 → FACT 내
   * SCORE_DRIFT. 새 출처 URL 은 기존과 다른 값을 사용해 URL_REPLACEMENT 를 중복 유발하지 않도록 한다.
   *
   * @param claimId 대상 Claim ID
   * @param newScore 새 점수
   */
  private ClaimCascadeResult buildNewResult(UUID claimId, int newScore) {
    ClaimVerificationSignal newSignal =
        new ClaimVerificationSignal(
            claimId, (short) 1, newScore, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT);
    EvidenceSnapshot evidence =
        new EvidenceSnapshot(
            "https://new-source.example.com/article-99",
            "새 출처",
            "새 기사 제목",
            "SUPPORTED",
            Collections.emptyMap(),
            Collections.emptyMap());
    return new ClaimCascadeResult(newSignal, List.of(evidence));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I1: happy path — supersede + 세션 재집계
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I1 happy path: 점수 차 16 → SCORE_DRIFT supersede + 세션 totalScore 재집계")
  void I1_happyPath_scoreDrift_supersedeAndSessionUpdate() {
    // Given: old score=99 (FACT), new score=83 (FACT), diff=16 > threshold(15) → SCORE_DRIFT
    FixtureResult fixture = saveFixture(99);
    VerificationResult old = fixture.result();
    UUID claimId = old.getClaim().getId();
    ClaimCascadeResult newResult = buildNewResult(claimId, 83);

    long countBefore = verificationResultRepository.count();

    // When
    reVerifyTransactionService.persistReverifyOutcome(old.getId(), newResult);

    // Then: 새 행 INSERT
    long countAfter = verificationResultRepository.count();
    assertThat(countAfter).isEqualTo(countBefore + 1);

    // 기존 행 supersede 마킹 검증
    VerificationResult superseded =
        verificationResultRepository.findById(old.getId()).orElseThrow();
    assertThat(superseded.isCurrent()).isFalse();
    assertThat(superseded.getSupersedeReason()).isEqualTo(SupersedeReason.SCORE_DRIFT);
    assertThat(superseded.getSupersededAt()).isNotNull();
    assertThat(superseded.getSupersededByResultId()).isNotNull();

    // 새 current 행
    VerificationResult newCurrent =
        verificationResultRepository.findByClaimIdAndSupersededAtIsNull(claimId).orElseThrow();
    assertThat(newCurrent.getScore()).isEqualTo((short) 83);
    assertThat(newCurrent.isCurrent()).isTrue();
    assertThat(newCurrent.getOriginalResultId()).isEqualTo(old.getId());

    // 세션 totalScore 재집계 — 83점으로 갱신 (DB 재조회로 LAZY 문제 회피)
    AnalysisSession session = analysisSessionRepository.findById(fixture.sessionId()).orElseThrow();
    assertThat(session.getTotalScore()).isEqualTo((short) 83);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I2: 4조건 미충족 — last_confirmed_at 갱신, 행 수 불변
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I2 4조건 미충족: old=95, new=95 동일 → 행 수 불변 + last_confirmed_at 갱신")
  void I2_noChange_confirmRechecked() {
    // Given: old score=95 (FACT), new score=95 (FACT), diff=0 → 4조건 모두 미충족
    FixtureResult fixture = saveFixture(95);
    VerificationResult old = fixture.result();
    UUID claimId = old.getClaim().getId();
    ClaimVerificationSignal sameSignal =
        new ClaimVerificationSignal(
            claimId, (short) 1, 95, ClaimScoreStatus.SCORABLE, SourceTransparency.EXPLICIT);
    // 동일 URL 사용 → URL_REPLACEMENT 도 발동 안 함 (기존 URL 과 동일)
    EvidenceSnapshot sameEvidence =
        new EvidenceSnapshot(
            "https://source.example.com/article-1", // saveFixture 와 동일 URL
            "기존 출처",
            "기존 기사",
            "SUPPORTED",
            Collections.emptyMap(),
            Collections.emptyMap());
    ClaimCascadeResult noChangeResult = new ClaimCascadeResult(sameSignal, List.of(sameEvidence));

    long countBefore = verificationResultRepository.count();

    // When
    reVerifyTransactionService.persistReverifyOutcome(old.getId(), noChangeResult);

    // Then: 행 수 불변
    assertThat(verificationResultRepository.count()).isEqualTo(countBefore);

    // last_confirmed_at 갱신
    VerificationResult reloaded = verificationResultRepository.findById(old.getId()).orElseThrow();
    assertThat(reloaded.getLastConfirmedAt()).isNotNull();
    assertThat(reloaded.isCurrent()).isTrue();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I3: 쿨다운 — verifiedAt=now() → TooManyRequestsException
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I3 쿨다운: verifiedAt=지금 → validateAndGet TooManyRequestsException(429)")
  void I3_cooldown_tooManyRequests() {
    // Given: verifiedAt=now() — 쿨다운(PT10M) 내에 있으므로 429 예상
    // @TestPropertySource cooldown=PT10M → production bean 이 PT10M 으로 동작
    // validateAndGet 내부: lastActivity=verifiedAt=now() → now().isAfter(now()-PT10M) → true → 429
    FixtureResult freshFixture = saveFixtureWithVerifiedAt(80, LocalDateTime.now());
    UUID freshId = freshFixture.result().getId();

    // When / Then: production service 의 ReVerifyTransactionService.validateAndGet 이
    // 실제 TooManyRequestsException 을 던지는지 검증
    assertThatThrownBy(() -> reVerifyTransactionService.validateAndGet(freshId))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("쿨다운");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I4: superseded 행에 validateAndGet → ConflictException
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I4 superseded 행 validateAndGet → ConflictException(409)")
  void I4_supersededResult_conflictException() {
    // Given: I1 흐름으로 supersede 수행
    FixtureResult fixture = saveFixture(99);
    VerificationResult old = fixture.result();
    UUID claimId = old.getClaim().getId();
    reVerifyTransactionService.persistReverifyOutcome(old.getId(), buildNewResult(claimId, 83));

    // superseded 여부 확인
    VerificationResult superseded =
        verificationResultRepository.findById(old.getId()).orElseThrow();
    assertThat(superseded.isCurrent()).isFalse();

    // When/Then: superseded 행에 validateAndGet → ConflictException
    assertThatThrownBy(() -> reVerifyTransactionService.validateAndGet(old.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("이미 정정된 결과");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I5: 부재 id → NotFoundException
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I5 부재 id → validateAndGet NotFoundException(404)")
  void I5_notFoundId_notFoundException() {
    UUID nonExistentId = UUID.randomUUID();

    assertThatThrownBy(() -> reVerifyTransactionService.validateAndGet(nonExistentId))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("검증 결과를 찾을 수 없습니다");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I6: 회귀 핵심 — supersede 후 getVerification 이 최신 결과 1건만 반환
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I6 회귀 핵심: supersede 후 getVerification(articleId) 이 옛 결과 반환 안 함 — 최신 1건")
  void I6_regression_getVerificationReturnsOnlyCurrentResult() {
    // Given
    FixtureResult fixture = saveFixture(99);
    VerificationResult old = fixture.result();
    UUID claimId = old.getClaim().getId();
    // articleId: claim.article 은 LAZY 이므로 DB 재조회로 가져옴
    UUID articleId = articleRepository.findBySessionId(fixture.sessionId()).orElseThrow().getId();
    reVerifyTransactionService.persistReverifyOutcome(old.getId(), buildNewResult(claimId, 83));

    // When
    var response = articleVerificationService.getVerification(articleId);

    // Then: claim 1건에 대해 결과 1건(최신, score=83)만 반환 — supersede 된 99점 결과는 포함 안 됨
    assertThat(response.getClaims()).hasSize(1);
    var claimItem = response.getClaims().get(0);
    // score=83 이므로 FACT 밴드(80~100) → truthLabel="FACT"
    assertThat(claimItem.getTruthLabel()).isNotNull();
    // claimScoreStatus 는 SCORABLE 이면 null (RC-06 상호 배타)
    assertThat(claimItem.getClaimScoreStatus()).isNull();

    // DB 레벨로도 current 결과 1건 확인 (supersede 이전 결과가 포함 안 됨)
    var currentByClaimId = verificationResultRepository.findByClaimIdAndSupersededAtIsNull(claimId);
    assertThat(currentByClaimId).isPresent();
    assertThat(currentByClaimId.get().getScore()).isEqualTo((short) 83);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // I7: 동시 2요청 — advisory lock으로 supersede 마킹 1건, 결과 행 2, 예외 0
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("I7 동시 2요청: advisory lock으로 supersede 마킹 정확히 1건, 결과 행 = 원본1 + 신규1 = 2, 예외 0")
  void I7_concurrent_advisoryLockEnsuresSingleSupersede() throws InterruptedException {
    // Given
    FixtureResult fixture = saveFixture(99);
    VerificationResult old = fixture.result();
    UUID oldId = old.getId();
    UUID claimId = old.getClaim().getId();

    // 두 스레드가 동시에 persistReverifyOutcome 호출
    CountDownLatch latch = new CountDownLatch(1);
    List<Throwable> errors = Collections.synchronizedList(new java.util.ArrayList<>());

    Runnable task =
        () -> {
          try {
            latch.await(5, TimeUnit.SECONDS);
            // 각 스레드가 약간 다른 점수를 전달해도 advisory lock이 하나만 처리
            ClaimCascadeResult result = buildNewResult(claimId, 83);
            reVerifyTransactionService.persistReverifyOutcome(oldId, result);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Throwable e) {
            errors.add(e);
          }
        };

    ExecutorService executor = Executors.newFixedThreadPool(2);
    executor.submit(task);
    executor.submit(task);

    // 동시 출발
    latch.countDown();
    executor.shutdown();
    boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);

    // Then: 예외 0 건
    assertThat(finished).as("executor 타임아웃 없이 완료").isTrue();
    assertThat(errors).as("동시 2요청 예외 없음").isEmpty();

    // 전체 결과 행 수: 원본 1 + 신규 1 = 2
    long totalResults = verificationResultRepository.count();
    assertThat(totalResults).as("원본 행 + 신규 행 = 2").isEqualTo(2L);

    // supersede 마킹은 정확히 1건 (늦은 요청은 no-op)
    long supersededCount =
        verificationResultRepository.findAll().stream().filter(r -> !r.isCurrent()).count();
    assertThat(supersededCount).as("supersede 마킹 정확히 1건").isEqualTo(1L);

    // current 행(partial unique uq_vr_claim_current)은 1건
    var current = verificationResultRepository.findByClaimIdAndSupersededAtIsNull(claimId);
    assertThat(current).as("claim 당 current 행 1건").isPresent();
  }
}
