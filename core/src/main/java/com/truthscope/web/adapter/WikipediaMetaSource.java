package com.truthscope.web.adapter;

import com.truthscope.web.adapter.datasource.AdapterMetadata;
import com.truthscope.web.adapter.datasource.AdapterQuery;
import com.truthscope.web.adapter.datasource.HealthStatus;
import com.truthscope.web.adapter.datasource.WikipediaMetaSignal;
import java.io.IOException;
import java.util.List;

/**
 * Wikipedia Tier 2 meta-source 전용 인터페이스.
 *
 * <p>domain-logic.md Wikipedia placement 정합 — Tier 1 evidence가 아닌 meta-source 역할.
 * DataSourceAdapter의 fetch()/parse() 계약이 일반 datasource claim용이므로 분리한다. 사용자 의도: lateral
 * reading(Wineburg & McGrew 2017) 원칙을 타입 수준에서 강제.
 *
 * <p>ADR-023 참조 — WikipediaMetaSource 전용 interface 분리 결정. ADR-006 OCP/D3 비전 정합 — DataSourceAdapter
 * sealed interface 패턴 방지.
 *
 * <p>Spring 무관 (core 모듈). @Component/RestClient 의존은 app 모듈 WikipediaAdapter에서만.
 */
public interface WikipediaMetaSource {

  /**
   * Wikipedia meta signal 조회. Tier 2 보조 evidence로만 사용.
   *
   * <p>Vandalism mitigation 내장 — UNSTABLE/UNKNOWN 판정 시 빈 리스트 반환.
   *
   * @param query 어댑터 쿼리 (keyword=주체명, lang 무시 — 내부에서 ko→en 순 시도)
   * @return Wikipedia meta signals (extract 본문 미포함 — lateral reading 원칙)
   * @throws IOException 네트워크 또는 파싱 실패
   */
  List<WikipediaMetaSignal> fetchMetaSignal(AdapterQuery query) throws IOException;

  /** 어댑터 health 상태 — 외부 API 가용성 확인 */
  HealthStatus health();

  /** 어댑터 메타데이터 — 이름/버전/region 등 */
  AdapterMetadata metadata();

  /**
   * 테스트용 고정 fixture — 외부 API 호출 없이 재현 가능한 WikipediaMetaSignal 5건 이상 반환. DataSourceAdapter.fixture()
   * 패턴 정합 (backend-datasource-adapter.md 수용 기준).
   */
  List<WikipediaMetaSignal> fixtureAsMetaSignal();
}
