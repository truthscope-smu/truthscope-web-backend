package com.truthscope.web.drift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 구조적 drift 가드 — Flyway V1 적용 스키마에 대해 Hibernate {@code ddl-auto=validate} 통과 여부로 "엔티티 == V1"
 * parity를 강제한다.
 *
 * <p>기존 {@code AbstractIntegrationTest}는 {@code create-drop}(엔티티가 스키마 생성)이라 ERD/V1 drift를 구조적으로 절대
 * 못 잡는다 — 이것이 3 drift가 여태 미검출된 root cause였다. 본 테스트는 운영과 동일하게 Flyway가 V1로 스키마를 만들고, Hibernate가 엔티티를
 * 그 스키마에 validate한다. V1이 엔티티에서 어긋나면 컨텍스트 부트가 실패해 본 테스트가 깨진다(향후 마이그레이션 회귀 가드).
 *
 * <p>{@code AbstractIntegrationTest}를 의도적으로 상속하지 않음 — 그 base는 create-drop + flyway 비활성을 강제하므로 본 검증과
 * 상반된다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"
    })
@DisplayName("Flyway V1 == 엔티티 parity (ddl-auto=validate)")
class FlywaySchemaParityTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }

  @Test
  @DisplayName("Flyway V1 적용 후 엔티티 validate 통과 — 컨텍스트 부트 = parity 입증")
  void entitiesValidateAgainstFlywaySchema() {
    // 컨텍스트가 떴다는 것 자체가 Flyway V1 적용 + Hibernate validate 통과를 의미한다.
    // validate 실패 시 ApplicationContext 로딩 실패 → 본 테스트 실패.
  }
}
