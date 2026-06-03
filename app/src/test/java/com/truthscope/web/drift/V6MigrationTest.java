package com.truthscope.web.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V6 마이그레이션 스키마 drift 가드.
 *
 * <p>Flyway V1..V6를 순차 적용한 스키마에 대해 information_schema 조회로 다음을 단언한다:
 *
 * <ul>
 *   <li>verification_results.tier3_reason 컬럼 존재 + 관련 CHECK 제약 존재
 *   <li>claims 에 attribution 3 컬럼(speaker_name / is_quoted_claim / original_context) 존재
 *   <li>verification_trace 에 metadata 3 컬럼(prompt_version / schema_version / decision_source) 존재 +
 *       ck_verification_trace_decision_source CHECK 제약 존재
 *   <li>analysis_sessions.coverage 컬럼 타입이 JSONB
 *   <li>ck_analysis_sessions_total_score_range CHECK 제약 존재
 * </ul>
 *
 * <p>entity 변경(T2-3 µ2.2)에 의존하지 않고 raw information_schema 쿼리만 사용한다. T2-1 단독 scope = DB 스키마 검증.
 *
 * <p>Singleton Testcontainers + @ServiceConnection 패턴 (VerificationTraceSchemaIntegrationTest 정합).
 * AbstractIntegrationTest 상속 금지 — 그 base는 create-drop + flyway 비활성이라 V6 drift 검증 불가.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@DisplayName("V6 cascade metadata 스키마 drift 가드 (Flyway V1..V6 적용 후)")
class V6MigrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @Autowired JdbcTemplate jdbcTemplate;

  // ── 헬퍼 ────────────────────────────────────────────────────────────────

  /** information_schema.columns 에서 지정 테이블에 해당 컬럼이 존재하는지 확인. */
  private boolean columnExists(String tableName, String columnName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' "
                + "AND table_name = ? "
                + "AND column_name = ?",
            Integer.class,
            tableName,
            columnName);
    return count != null && count > 0;
  }

  /** information_schema.columns 에서 지정 테이블·컬럼의 data_type을 반환. */
  private String columnDataType(String tableName, String columnName) {
    return jdbcTemplate.queryForObject(
        "SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = 'public' "
            + "AND table_name = ? "
            + "AND column_name = ?",
        String.class,
        tableName,
        columnName);
  }

  /** information_schema.check_constraints 에서 해당 constraint_name이 존재하는지 확인. */
  private boolean checkConstraintExists(String constraintName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = ?",
            Integer.class,
            constraintName);
    return count != null && count > 0;
  }

  // ── Section 1: verification_results.tier3_reason ───────────────────────

  @Test
  @DisplayName("V6-1 verification_results.tier3_reason 컬럼 존재")
  void verificationResults_tier3ReasonColumn_exists() {
    assertThat(columnExists("verification_results", "tier3_reason"))
        .as("verification_results.tier3_reason 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-2 ck_verification_results_tier3_reason_consistency CHECK 제약 존재")
  void verificationResults_tier3ReasonCheckConstraint_exists() {
    assertThat(checkConstraintExists("ck_verification_results_tier3_reason_consistency"))
        .as("ck_verification_results_tier3_reason_consistency CHECK 제약이 존재해야 한다")
        .isTrue();
  }

  // ── Section 2: claims attribution 3 컬럼 ──────────────────────────────

  @Test
  @DisplayName("V6-3 claims.speaker_name 컬럼 존재")
  void claims_speakerNameColumn_exists() {
    assertThat(columnExists("claims", "speaker_name"))
        .as("claims.speaker_name 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-4 claims.is_quoted_claim 컬럼 존재")
  void claims_isQuotedClaimColumn_exists() {
    assertThat(columnExists("claims", "is_quoted_claim"))
        .as("claims.is_quoted_claim 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-5 claims.original_context 컬럼 존재")
  void claims_originalContextColumn_exists() {
    assertThat(columnExists("claims", "original_context"))
        .as("claims.original_context 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  // ── Section 3: verification_trace metadata 3 컬럼 ─────────────────────

  @Test
  @DisplayName("V6-6 verification_trace.prompt_version 컬럼 존재")
  void verificationTrace_promptVersionColumn_exists() {
    assertThat(columnExists("verification_trace", "prompt_version"))
        .as("verification_trace.prompt_version 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-7 verification_trace.schema_version 컬럼 존재")
  void verificationTrace_schemaVersionColumn_exists() {
    assertThat(columnExists("verification_trace", "schema_version"))
        .as("verification_trace.schema_version 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-8 verification_trace.decision_source 컬럼 존재")
  void verificationTrace_decisionSourceColumn_exists() {
    assertThat(columnExists("verification_trace", "decision_source"))
        .as("verification_trace.decision_source 컬럼이 V6 마이그레이션으로 추가되어야 한다")
        .isTrue();
  }

  @Test
  @DisplayName("V6-9 ck_verification_trace_decision_source CHECK 제약 존재")
  void verificationTrace_decisionSourceCheckConstraint_exists() {
    assertThat(checkConstraintExists("ck_verification_trace_decision_source"))
        .as("ck_verification_trace_decision_source CHECK 제약이 존재해야 한다")
        .isTrue();
  }

  // ── Section 4: analysis_sessions.coverage JSONB 타입 ──────────────────

  @Test
  @DisplayName("V6-10 analysis_sessions.coverage 컬럼 타입 = jsonb (VARCHAR(10) → JSONB 변환)")
  void analysisSessions_coverageColumn_isJsonb() {
    String dataType = columnDataType("analysis_sessions", "coverage");
    assertThat(dataType)
        .as("analysis_sessions.coverage 는 V6 마이그레이션으로 jsonb 타입이 되어야 한다")
        .isEqualTo("jsonb");
  }

  // ── Section 5: analysis_sessions.total_score CHECK 제약 ───────────────

  @Test
  @DisplayName("V6-11 ck_analysis_sessions_total_score_range CHECK 제약 존재")
  void analysisSessions_totalScoreRangeCheckConstraint_exists() {
    assertThat(checkConstraintExists("ck_analysis_sessions_total_score_range"))
        .as("ck_analysis_sessions_total_score_range CHECK 제약이 존재해야 한다")
        .isTrue();
  }

  // ── 컨텍스트 부트 자체 = Flyway V1..V6 + validate 통과 증명 ────────────

  @Test
  @DisplayName("V6-12 Flyway V1..V6 적용 + Hibernate ddl-auto=validate 통과 — 컨텍스트 부트 = 스키마 정합 입증")
  void flywayV1toV6_entitiesValidate_contextBootSucceeds() {
    // 컨텍스트가 떴다는 것 자체가 Flyway V1..V6 순차 적용 + Hibernate validate 통과를 의미한다.
    // validate 실패 시 ApplicationContext 로딩 실패 → 테스트 실패.
    // entity 변경(T2-3 µ2.2)이 완료되기 전에는 새 컬럼에 매핑된 entity 필드가 없으므로
    // validate는 "DB에 있지만 entity에 없는 컬럼" 방향 오류를 잡지 않는다 — 이 방향은 Hibernate 기본 동작상 허용.
    // 반대 방향(entity에 있는데 DB에 없는)은 validate 실패 → 본 테스트가 catch한다.
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' ORDER BY table_name",
            String.class);
    assertThat(tables).isNotEmpty();
  }
}
