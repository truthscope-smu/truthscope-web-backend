package com.truthscope.web.drift;

import com.truthscope.web.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 엔티티에서 권위 있는 스키마 DDL을 Hibernate로 추출한다 (Flyway V1의 parity-보장 기반).
 *
 * <p>손으로 V1을 쓰면 컬럼 타입/길이/제약이 추측이 된다. 본 테스트는 컨텍스트 부트 시 Hibernate가 엔티티로부터 생성하는 create 스크립트를 파일로 떨군다.
 * 그 산출물이 곧 엔티티-진실 스키마이며 V1__init.sql의 출처가 된다. (특성화/추출 테스트 — 회귀 가드 아님)
 */
@TestPropertySource(
    properties = {
      "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
      "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/entity-schema.sql",
      "spring.jpa.properties.hibernate.hbm2ddl.delimiter=;",
      "spring.jpa.properties.hibernate.format_sql=true"
    })
class EntitySchemaExportTest extends AbstractIntegrationTest {

  @Test
  void exportEntityDerivedSchema() throws Exception {
    Path out = Path.of("build/entity-schema.sql");
    // EMF 부트 타이밍에 스크립트가 기록됨. 존재/비어있지 않음만 확인 (내용 검증은 사람이 수행).
    org.junit.jupiter.api.Assertions.assertTrue(
        Files.exists(out) && Files.size(out) > 0, "엔티티 파생 스키마 스크립트가 생성되어야 함");
  }
}
