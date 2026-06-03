package com.truthscope.web.drift;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V2/V3 무결성 hardening 회귀 가드 — Flyway V1 + V2 + V3를 순차 적용한 스키마에서 도메인 불변식이 DB 레벨로 강제됨을 단언한다.
 *
 * <p><b>핵심 불변식 (Phase 54 D8):</b> {@code verification_results}는 "모르면 모른다" 원칙을 DB CHECK로 박제한다 — Tier
 * 3은 score가 반드시 NULL(점수 부여 금지), Tier 1/2는 score가 반드시 0~100 사이 비-NULL. {@code
 * .claude/rules/domain-logic.md}의 3-Tier Cascade 규칙이 코드뿐 아니라 스키마에서도 깨지지 않게 한다.
 *
 * <p><b>V3 추가 불변식 (sprint-2 semantic-cache-correction-layer):</b> {@code verdict} 컬럼이 {@code NOT
 * NULL}로 추가되고, 허용 값 CHECK({@code SUPPORTED|CONTRADICTED|INSUFFICIENT|TIME_SENSITIVE|OUT_OF_SCOPE})가
 * 강제된다. 무결성 기준이 {@code label NOT NULL}에서 {@code verdict NOT NULL + 5종 CHECK}로 이전됐다(DISCUSS 11-11 =
 * 1b, ADR-014 Accepted). {@code label}은 V3부터 nullable + deprecated.
 *
 * <p><b>pass-only 금지 (feedback_bdd_tdd_not_pass_only):</b> 통과 케이스만이 아니라 위반 케이스가 실제로
 * 거부됨(SQLException)을 함께 단언한다. tier=1 + score=NULL 케이스는 SQL 3치 논리(three-valued logic) 구멍 — 단순 {@code
 * BETWEEN}만으로는 통과해버리는 — 을 V2 CHECK가 {@code score IS NOT NULL} 항으로 닫음을 검증한다.
 *
 * <p>{@link ErdContractDriftReproductionTest}와 같은 raw-JDBC 패턴 — {@code AbstractIntegrationTest}는
 * create-drop이라 V2/V3 ALTER 제약을 검증할 수 없다. 가변 값은 {@link PreparedStatement} 바인딩으로 전달한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V2/V3 무결성 hardening 회귀 가드 (V1 + V2 + V3 적용 후)")
class VerificationIntegrityMigrationTest {

  @Container
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String INSERT_RESULT =
      "INSERT INTO verification_results "
          + "(id, claim_id, tier, label, verdict, score, reason, disclaimer, verified_at, created_at, updated_at) "
          + "VALUES (gen_random_uuid(), NULL, ?, ?, ?, ?, ?, NULL, ?, now(), now())";

  private static final String INSERT_SOURCE =
      "INSERT INTO verify_sources (id, result_id, title, publisher, stance, created_at, updated_at) "
          + "VALUES (gen_random_uuid(), ?, 't', 'p', ?, now(), now())";

  @BeforeAll
  static void applyMigrations() throws Exception {
    try (Connection c = conn();
        Statement st = c.createStatement()) {
      st.execute(readMigration("/db/migration/V1__init_schema.sql"));
      st.execute(readMigration("/db/migration/V2__hardening.sql"));
      st.execute(readMigration("/db/migration/V3__add_verdict_column.sql"));
    }
  }

  private static String readMigration(String resource) throws Exception {
    try (InputStream in = VerificationIntegrityMigrationTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " 리소스를 찾을 수 없음");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Connection conn() throws SQLException {
    return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  private static Timestamp now() {
    return Timestamp.valueOf(LocalDateTime.now());
  }

  /** verification_results 한 건을 INSERT. 가변 컬럼은 바인딩, FK claim_id는 NULL. */
  private static void insertResult(
      Short tier, String label, String verdict, Short score, String reason, Timestamp verifiedAt)
      throws SQLException {
    try (Connection c = conn();
        PreparedStatement ps = c.prepareStatement(INSERT_RESULT)) {
      ps.setObject(1, tier);
      ps.setObject(2, label);
      ps.setObject(3, verdict);
      ps.setObject(4, score);
      ps.setObject(5, reason);
      ps.setObject(6, verifiedAt);
      ps.executeUpdate();
    }
  }

  /** 유효한 verification_results(Tier 3) 한 건을 INSERT하고 그 id를 반환 — verify_sources FK 부모용. */
  private static UUID insertParentResult() throws SQLException {
    try (Connection c = conn();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "INSERT INTO verification_results "
                    + "(id, claim_id, tier, label, verdict, score, reason, verified_at, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), NULL, 3, '검증 불가', 'INSUFFICIENT', NULL, 'r', now(), now(), now()) "
                    + "RETURNING id")) {
      rs.next();
      return rs.getObject("id", UUID.class);
    }
  }

  private static void insertSource(UUID resultId, String stance) throws SQLException {
    try (Connection c = conn();
        PreparedStatement ps = c.prepareStatement(INSERT_SOURCE)) {
      ps.setObject(1, resultId);
      ps.setObject(2, stance);
      ps.executeUpdate();
    }
  }

  // ── verification_results 복합 불변식 (D8) ──────────────────────────────

  @Test
  @DisplayName("A. Tier 3 + score NULL — 통과 (모르면 모른다: 점수 미부여)")
  void tier3NullScore_accepted() {
    assertThatCode(() -> insertResult((short) 3, "검증 불가", "INSUFFICIENT", null, "r", now()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("B. Tier 3 + score 비-NULL(50) — 위반 (Tier 3에 점수 부여 금지)")
  void tier3NonNullScore_rejected() {
    assertThatThrownBy(
            () -> insertResult((short) 3, "검증 불가", "INSUFFICIENT", (short) 50, "r", now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("C. Tier 1 + score 범위 외(150) — 위반 (0~100 초과)")
  void tier1ScoreOutOfRange_rejected() {
    assertThatThrownBy(
            () -> insertResult((short) 1, "기관 검증 완료", "SUPPORTED", (short) 150, "r", now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("D. Tier 1 + score NULL — 위반 (3치 논리 구멍: score IS NOT NULL 항이 닫음)")
  void tier1NullScore_rejected() {
    assertThatThrownBy(() -> insertResult((short) 1, "기관 검증 완료", "SUPPORTED", null, "r", now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("E. Tier 1 + score 0~100(50) — 통과 (happy path)")
  void tier1ValidScore_accepted() {
    assertThatCode(() -> insertResult((short) 1, "기관 검증 완료", "SUPPORTED", (short) 50, "r", now()))
        .doesNotThrowAnyException();
  }

  // ── verification_results NOT NULL ────────────────────────────────────

  @Test
  @DisplayName("F. tier NULL — 위반 (NOT NULL — 복합 CHECK의 3치 논리 구멍도 함께 닫음)")
  void nullTier_rejected() {
    assertThatThrownBy(() -> insertResult(null, "검증 불가", "INSUFFICIENT", null, "r", now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("G. label NULL — 통과 (V3 label DROP NOT NULL — 무결성 기준이 verdict로 이전)")
  void nullLabel_accepted() {
    assertThatCode(() -> insertResult((short) 3, null, "INSUFFICIENT", null, "r", now()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("H. reason NULL — 위반 (NOT NULL)")
  void nullReason_rejected() {
    assertThatThrownBy(() -> insertResult((short) 3, "검증 불가", "INSUFFICIENT", null, null, now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("I. verified_at NULL — 위반 (NOT NULL)")
  void nullVerifiedAt_rejected() {
    assertThatThrownBy(() -> insertResult((short) 3, "검증 불가", "INSUFFICIENT", null, "r", null))
        .isInstanceOf(SQLException.class);
  }

  // ── verify_sources.stance CHECK ──────────────────────────────────────

  @Test
  @DisplayName("J. stance 'supports' — 통과 (Tier 2 허용 값)")
  void stanceSupports_accepted() {
    assertThatCode(() -> insertSource(insertParentResult(), "supports")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("K. stance NULL — 통과 (Tier 1은 stance 미사용)")
  void stanceNull_accepted() {
    assertThatCode(() -> insertSource(insertParentResult(), null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("L. stance 'maybe' — 위반 (CHECK 허용 값 아님)")
  void stanceInvalid_rejected() {
    assertThatThrownBy(() -> insertSource(insertParentResult(), "maybe"))
        .isInstanceOf(SQLException.class);
  }

  // ── articles.session_id NOT NULL ─────────────────────────────────────

  @Test
  @DisplayName("M. articles.session_id NULL — 위반 (NOT NULL — attachTo 누락 시 DB가 차단)")
  void articleNullSession_rejected() {
    assertThatThrownBy(
            () -> {
              try (Connection c = conn();
                  Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO articles "
                        + "(id, session_id, title, body, source_type, extracted_at, created_at, updated_at) "
                        + "VALUES (gen_random_uuid(), NULL, 't', 'b', 'URL_INPUT', now(), now(), now())");
              }
            })
        .isInstanceOf(SQLException.class);
  }

  // ── verification_results.verdict (V3 — 무결성 기준 이전) ───────────────

  @Test
  @DisplayName("N1. verdict 'SUPPORTED' — 통과 (Verdict 5종 허용 값)")
  void verdictValid_accepted() {
    assertThatCode(() -> insertResult((short) 1, "기관 검증 완료", "SUPPORTED", (short) 50, "r", now()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("N2. verdict 'INVALID' — 위반 (ck_verification_results_verdict CHECK)")
  void verdictInvalid_rejected() {
    assertThatThrownBy(() -> insertResult((short) 1, "기관 검증 완료", "INVALID", (short) 50, "r", now()))
        .isInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("N3. verdict NULL — 위반 (NOT NULL — 무결성 기준이 verdict로 이전)")
  void verdictNull_rejected() {
    assertThatThrownBy(() -> insertResult((short) 3, "검증 불가", null, null, "r", now()))
        .isInstanceOf(SQLException.class);
  }
}
