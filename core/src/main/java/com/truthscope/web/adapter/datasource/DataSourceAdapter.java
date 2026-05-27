package com.truthscope.web.adapter.datasource;

import java.io.IOException;
import java.util.List;

/**
 * 외부 검증 데이터 소스 추상화 (Tier 1 cascade 진입점 전용).
 *
 * <p>Round 5 LSP 해결 (Amend 5 + ADR-023) 후 Wikipedia 등 Tier 2 meta-source는 본 interface가 아닌 전용 {@code
 * WikipediaMetaSource} interface를 구현한다. 본 interface는 fact-evidence 수집 어댑터 전용.
 *
 * <p>Spring 무관 (core 모듈). HTTP/DI/API 키 의존은 app 모듈 구현체에서 처리. ADR-006 OCP/D3 비전 정합 — 새 어댑터 추가 시
 * VerificationPipeline 수정 불필요.
 *
 * <p>참고: docs/guides/backend-datasource-adapter.md §3.1 (BE #22 scaffold)
 */
public interface DataSourceAdapter {

  RawResponse fetch(AdapterQuery query) throws IOException;

  /**
   * 일반 어댑터(Tier 1 등)는 List&lt;DatasourceClaim&gt; 반환. WikipediaAdapter는 이 메서드를 재정의하지 않고
   * parseAsMetaSignal()을 별도 제공 (H1 amend — Tier 2 반환형 분리).
   */
  List<DatasourceClaim> parse(RawResponse rawResponse);

  HealthStatus health();

  AdapterMetadata metadata();

  /**
   * 테스트용 고정 fixture (D3 재현성 — 외부 API 호출 없이 결과 재현). 최소 5건 반환 의무 (backend-datasource-adapter.md §3.3
   * 수용 기준).
   *
   * <p>[Neo-H codex Round 2 amend] 반환형을 {@code List<DatasourceClaim>}으로 통일. 이전 {@code
   * List<ExtractedClaim>}은 rename 이전 타입으로 WikipediaAdapter.fixture() override와 반환형 불일치 → 컴파일 에러 발생.
   * DatasourceClaim 단일 타입으로 통일. WikipediaAdapter는 fixture() override에서 {@code List.of()} 반환 —
   * DatasourceClaim 경로 차단 유지.
   */
  List<DatasourceClaim> fixture();
}
