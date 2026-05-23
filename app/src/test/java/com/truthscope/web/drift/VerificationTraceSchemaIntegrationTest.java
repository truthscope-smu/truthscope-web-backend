package com.truthscope.web.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truthscope.web.entity.DataSourceSnapshot;
import com.truthscope.web.entity.VerificationTrace;
import com.truthscope.web.repository.DataSourceSnapshotRepository;
import com.truthscope.web.repository.VerificationTraceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// 실행 컨텍스트 메모:
// - Testcontainers static 컨테이너 — class 단위 1회 spin-up (Spring Boot 3.5.13 + @ServiceConnection 패턴).
// - Flyway clean default disabled (Flyway 9+ 기준) 단계로 컨테이너 재사용 시 V1 ~ V5 멱등 migrate.
// - @Transactional 클래스 레벨 — 각 @Test 메서드 자동 rollback 단계로 seedVerificationResult가 다음 테스트에 누출 안 됨.
// - constraint name 단언은 Hibernate ConstraintViolationException cause chain의 getConstraintName() 사용
//   (Spring DataIntegrityViolationException top-level getMessage()에 constraint name 포함 보장 없음, R2-1
// 정정).
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@Transactional
@DisplayName("V5 verification_trace + data_source_snapshots 스키마 통합 테스트")
class VerificationTraceSchemaIntegrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @Autowired VerificationTraceRepository traceRepository;
  @Autowired DataSourceSnapshotRepository snapshotRepository;

  @PersistenceContext EntityManager entityManager;

  // 유효 FK 제공을 위한 verification_results 선행 INSERT 결과 id.
  // V1 verification_results 9 컬럼 + V2 tier/score CHECK + V3 verdict NOT NULL + label DROP NOT NULL.
  // claim_id는 V1 schema상 nullable 단계로 NULL 사용 가능 (Claim 의존성 회피).
  // label은 V3에서 DROP NOT NULL 적용 후 NULL 허용 단계로 정합 (R2-2 명시).
  // native SQL 사용 — JPA로 VerificationResult save 시 Claim FK 또는 verdict enum 매핑 등
  // 추가 코드 결합 필요. T6 scope = schema/constraint 검증으로 한정.
  private UUID validVerificationResultId;

  /**
   * Hibernate ConstraintViolationException을 cause chain에서 찾아 getConstraintName() 단언. Spring
   * DataIntegrityViolationException top-level getMessage()는 Hibernate 버전 + Postgres JDBC 메시지 포맷에 따라
   * constraint name이 포함되지 않을 수 있어, cause chain의 Hibernate 전용 API가 source of truth (R2-1 / CX2-1
   * 정정).
   */
  private void assertConstraintViolation(ThrowingCallable when, String expectedConstraint) {
    assertThatThrownBy(when)
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            ex -> {
              Throwable cur = (Throwable) ex;
              while (cur != null && !(cur instanceof ConstraintViolationException)) {
                cur = cur.getCause();
              }
              assertThat(cur)
                  .as("ConstraintViolationException not found in cause chain of %s", ex)
                  .isNotNull();
              assertThat(((ConstraintViolationException) cur).getConstraintName())
                  .as("constraint name mismatch")
                  .isEqualTo(expectedConstraint);
            });
  }

  @BeforeEach
  void seedVerificationResult() {
    validVerificationResultId = UUID.randomUUID();
    entityManager
        .createNativeQuery(
            "INSERT INTO verification_results "
                + "(id, claim_id, tier, score, verdict, label, reason, disclaimer, "
                + " verified_at, created_at, updated_at) "
                + "VALUES (?, NULL, 1, 50, 'SUPPORTED', NULL, 'seed', NULL, "
                + " NOW(), NOW(), NOW())")
        .setParameter(1, validVerificationResultId)
        .executeUpdate();
  }

  @Test
  @DisplayName(
      "R-1 data_source_snapshots happy path INSERT 성공 + JSONB roundtrip + retrieved_at reload 검증")
  void insertDataSourceSnapshot_happyPath() {
    DataSourceSnapshot snapshot =
        DataSourceSnapshot.builder()
            .adapterName("google-fc")
            .queryHash("sha256-abc123")
            .sourceVersion("v1")
            .responseBody(Map.of("claims", "[]", "status", "ok"))
            .build();
    DataSourceSnapshot saved = snapshotRepository.saveAndFlush(snapshot);
    assertThat(saved.getId()).isNotNull();

    // R2-CX2-7 / R2-CX2-6: managed entity 의존 제거 단계로 clear 후 findById reload로 DB roundtrip 검증.
    entityManager.clear();
    DataSourceSnapshot reloaded = snapshotRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getRetrievedAt()).isNotNull();
    assertThat(reloaded.getResponseBody()).containsEntry("claims", "[]");
    assertThat(reloaded.getResponseBody()).containsEntry("status", "ok");
    assertThat(reloaded.getAdapterName()).isEqualTo("google-fc");
  }

  @Test
  @DisplayName(
      "R-2 data_source_snapshots UNIQUE 실제 충돌 — 동일 (adapter, query_hash, retrieved_at) 강제 INSERT 차단")
  void insertDataSourceSnapshot_uniqueConstraintViolation_real() {
    // @CreationTimestamp가 VM 기본이라 JPA save 마다 retrieved_at이 다름.
    // 동일 retrieved_at을 강제하려면 native query로 시각 값을 literal로 박는다.
    LocalDateTime fixedTs = LocalDateTime.of(2026, 5, 23, 12, 0, 0);
    String insertSql =
        "INSERT INTO data_source_snapshots "
            + "(id, adapter_name, query_hash, source_version, response_body, retrieved_at) "
            + "VALUES (?, 'google-fc', 'sha256-dup', 'v1', '{}'::jsonb, ?)";

    entityManager
        .createNativeQuery(insertSql)
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, fixedTs)
        .executeUpdate();
    entityManager.flush();

    assertConstraintViolation(
        () -> {
          entityManager
              .createNativeQuery(insertSql)
              .setParameter(1, UUID.randomUUID())
              .setParameter(2, fixedTs)
              .executeUpdate();
          entityManager.flush();
        },
        "uk_data_source_snapshots_adapter_query_retrieved");
  }

  @Test
  @DisplayName("R-3 verification_trace tier CHECK 위반 — 유효 FK + tier=4 → ck_verification_trace_tier")
  void insertVerificationTrace_tierCheckViolation() {
    VerificationTrace invalidTrace =
        VerificationTrace.builder()
            .verificationResultId(validVerificationResultId) // 유효 FK
            .tier((short) 4) // CHECK (tier IN (1, 2, 3)) 위반
            .adapterName("google-fc")
            .requestBody(Map.of("query", "test"))
            .responseBody(Map.of("result", "none"))
            .durationMs(100)
            .build();
    assertConstraintViolation(
        () -> traceRepository.saveAndFlush(invalidTrace), "ck_verification_trace_tier");
  }

  @Test
  @DisplayName(
      "R-4 verification_trace duration_ms CHECK 위반 — 유효 FK + duration_ms=-1 → ck_verification_trace_duration_nonneg")
  void insertVerificationTrace_durationNegativeViolation() {
    VerificationTrace invalidTrace =
        VerificationTrace.builder()
            .verificationResultId(validVerificationResultId) // 유효 FK
            .tier((short) 1)
            .adapterName("google-fc")
            .requestBody(Map.of("query", "test"))
            .responseBody(Map.of("result", "none"))
            .durationMs(-1) // CHECK (duration_ms >= 0) 위반
            .build();
    assertConstraintViolation(
        () -> traceRepository.saveAndFlush(invalidTrace), "ck_verification_trace_duration_nonneg");
  }

  @Test
  @DisplayName(
      "R-5 verification_trace orphan FK 위반 — 존재하지 않는 verification_result_id → fk_verification_trace_result")
  void insertVerificationTrace_orphanForeignKeyViolation() {
    // R1-CX4 회귀 C 분리. tier/duration은 valid 값 유지로 CHECK 위반 confound 제거.
    UUID nonExistentResultId = UUID.randomUUID();
    VerificationTrace orphan =
        VerificationTrace.builder()
            .verificationResultId(nonExistentResultId) // 존재하지 않는 FK 대상
            .tier((short) 1) // valid
            .adapterName("google-fc")
            .requestBody(Map.of("query", "test"))
            .responseBody(Map.of("result", "none"))
            .durationMs(100) // valid
            .build();
    assertConstraintViolation(
        () -> traceRepository.saveAndFlush(orphan), "fk_verification_trace_result");
  }

  @Test
  @DisplayName(
      "R-6 verification_trace Tier 1 nullable 필드 실제 저장 — prompt_git_sha/prompt_hash/model_version NULL 저장 후 reload 검증")
  void insertVerificationTrace_tier1NullablePromptFields_realPersist() {
    VerificationTrace tier1Trace =
        VerificationTrace.builder()
            .verificationResultId(validVerificationResultId) // 유효 FK
            .tier((short) 1)
            .adapterName("google-fc")
            .promptGitSha(null)
            .promptHash(null)
            .modelVersion(null)
            .requestBody(Map.of("query", "test"))
            .responseBody(Map.of("claims", "[]"))
            .durationMs(50)
            .build();
    VerificationTrace saved = traceRepository.saveAndFlush(tier1Trace);
    entityManager.clear(); // 1차 캐시 비워 reload 강제

    VerificationTrace reloaded = traceRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getPromptGitSha()).isNull();
    assertThat(reloaded.getPromptHash()).isNull();
    assertThat(reloaded.getModelVersion()).isNull();
    assertThat(reloaded.getTier()).isEqualTo((short) 1);
    // CX2-7 JSONB roundtrip 값 검증 — Hibernate 6 @JdbcTypeCode(SqlTypes.JSON) + Map<String, Object>
    // 매핑이 round trip 후 동일 키/값 보존하는지 확인.
    assertThat(reloaded.getRequestBody()).containsEntry("query", "test");
    assertThat(reloaded.getResponseBody()).containsEntry("claims", "[]");
  }

  @Test
  @DisplayName(
      "R-7 verification_trace JSONB NOT NULL DB 검증 — request_body NULL native INSERT 시 NOT NULL 위반")
  void insertVerificationTrace_requestBodyNullViolation_dbLevel() {
    // R2-5 / CX2-3 정정. Map.of()는 NPE이므로 Entity builder로 NULL JSONB 전달 불가.
    // native SQL로 NULL literal 강제 INSERT 단계로 PostgreSQL NOT NULL 제약 직접 검증.
    UUID newId = UUID.randomUUID();
    String insertSql =
        "INSERT INTO verification_trace "
            + "(id, verification_result_id, tier, adapter_name, "
            + " request_body, response_body, duration_ms, created_at) "
            + "VALUES (?, ?, 1, 'google-fc', NULL, '{}'::jsonb, 100, NOW())";

    assertThatThrownBy(
            () -> {
              entityManager
                  .createNativeQuery(insertSql)
                  .setParameter(1, newId)
                  .setParameter(2, validVerificationResultId)
                  .executeUpdate();
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("request_body"); // Postgres NOT NULL 메시지는 컬럼명 포함
  }
}
