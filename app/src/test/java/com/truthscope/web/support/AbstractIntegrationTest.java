package com.truthscope.web.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Boot 3.1+ {@code @ServiceConnection} 기반 Testcontainers 통합 테스트 base.
 *
 * <p>컨테이너는 <strong>JVM 단위 singleton</strong>으로 기동되며 모든 테스트 클래스가 공유한다. Spring TestContext 캐시 + 컨테이너
 * 라이프사이클 정합성 보장: {@code @Container} JUnit extension annotation은 의도적으로 미사용 — extension은 클래스 단위로
 * start/stop을 관리하므로 cached Spring context의 DataSource bean이 stale port를 가리키는 문제 발생. 대신 static
 * initializer에서 직접 {@link PostgreSQLContainer#start()} 호출 → JVM 종료까지 동일 컨테이너/포트 보장.
 *
 * <p>참고: <a
 * href="https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers">Testcontainers
 * Singleton Containers</a> + Spring Boot {@code @ServiceConnection} 가이드.
 *
 * <p>{@code WebEnvironment.RANDOM_PORT}로 Tomcat 기동 — 통합 테스트가 {@link
 * org.springframework.boot.test.web.client.TestRestTemplate}을 사용해 실제 HTTP 호출하기 위함. MOCK 기본값에서는
 * TestRestTemplate bean이 등록되지 않아 autowiring 실패.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"})
public abstract class AbstractIntegrationTest {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    POSTGRES.start();
  }
}
