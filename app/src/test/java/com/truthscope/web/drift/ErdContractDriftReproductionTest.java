package com.truthscope.web.drift;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 3 계약 drift 영구 회귀 가드 (flip 완료).
 *
 * <p><b>이력:</b> 본 클래스는 원래 ERD 문서 4절 DDL을 적용해 High-1(enum 대소문자) / Medium-1(BaseTime 컬럼) /
 * Medium-2b(source_type) 위반을 결정적으로 재현·확정했다(systematic-debugging Phase 1·3, SQLState 23514/42703
 * 입증). Phase 4에서 권위=엔티티-진실 + Flyway V1로 수정됐고, Supabase 실스키마 0 테이블(World C) 확인으로 이관 데이터가 없어 V1이 최초
 * 생성자가 됐다.
 *
 * <p><b>현재 의미:</b> 운영 스키마 출처인 {@code db/migration/V1__init_schema.sql}을 적용한 뒤, 과거에 실패하던 3 연산이 이제
 * 성공함을 단언한다. 누군가 후속 마이그레이션에서 enum CHECK를 소문자로 되돌리거나 created_at/updated_at·source_type을 제거하면 본 테스트가
 * 깨져 회귀를 잡는다. FK 컬럼은 NULL로 두어 각 단언을 해당 drift 축으로 격리(systematic-debugging: 한 번에 한 변수).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("3 계약 drift 회귀 가드 (V1 스키마 적용 후 정상 동작)")
class ErdContractDriftReproductionTest {

  @Container
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void applyV1() throws Exception {
    String ddl;
    try (InputStream in =
        ErdContractDriftReproductionTest.class.getResourceAsStream(
            "/db/migration/V1__init_schema.sql")) {
      if (in == null) {
        throw new IllegalStateException("V1__init_schema.sql 리소스를 찾을 수 없음");
      }
      ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection c = conn();
        Statement st = c.createStatement()) {
      st.execute(ddl);
    }
  }

  private static Connection conn() throws SQLException {
    return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  /** High-1 회귀 가드: @Enumerated(STRING) 대문자 값이 V1 CHECK에 수용돼야 함. */
  @Test
  @DisplayName("A. claims.importance — 대문자 'HIGH' INSERT 성공 (Drift A 해소 유지)")
  void high1_upperCaseEnumAccepted() {
    assertThatCode(
            () -> {
              try (Connection c = conn();
                  Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO claims (id, article_id, text, importance, sort_order) "
                        + "VALUES (gen_random_uuid(), NULL, 'x', 'HIGH', 0)");
              }
            })
        .doesNotThrowAnyException();
  }

  /** Medium-1 회귀 가드: BaseTimeEntity created_at/updated_at 컬럼이 존재해야 함. */
  @Test
  @DisplayName("B. verification_results — created_at/updated_at INSERT 성공 (Drift B 해소 유지)")
  void medium1_baseTimeColumnsPresent() {
    assertThatCode(
            () -> {
              try (Connection c = conn();
                  Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO verification_results "
                        + "(id, claim_id, tier, label, reason, verified_at, created_at, updated_at) "
                        + "VALUES (gen_random_uuid(), NULL, 1, 'AI 교차검증', 'r', now(), now(), now())");
              }
            })
        .doesNotThrowAnyException();
  }

  /** Medium-2b 회귀 가드: articles.source_type 컬럼이 존재해야 함. */
  @Test
  @DisplayName("C. articles.source_type — source_type INSERT 성공 (Drift C 해소 유지)")
  void medium2b_sourceTypeColumnPresent() {
    assertThatCode(
            () -> {
              try (Connection c = conn();
                  Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO articles "
                        + "(id, session_id, url, title, body, lang, extracted_at, source_type, created_at, updated_at) "
                        + "VALUES (gen_random_uuid(), NULL, 'http://x', 't', 'b', 'ko', now(), 'URL_INPUT', now(), now())");
              }
            })
        .doesNotThrowAnyException();
  }
}
