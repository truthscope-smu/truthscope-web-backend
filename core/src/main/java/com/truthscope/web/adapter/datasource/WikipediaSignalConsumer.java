package com.truthscope.web.adapter.datasource;

import java.util.List;

/**
 * WikipediaMetaSignal 소비 port interface (H3 codex Round 3 amend — port 명시).
 *
 * <p>WikipediaMetaSignal을 수신하여 Tier 2 전용 보조 경로(알림, 로그, 이벤트 버스 등)로 위임한다. FactcheckCacheRepository에
 * 저장하는 경로는 ArchUnit 4번째 룰로 절대 차단됨.
 *
 * <p>구현체는 WikipediaMetaSignal 처리 시 factcheck_cache 외 경로(Tier 2 전용 sink)로만 위임해야 한다.
 * domain-logic.md:78 "Wikipedia = Tier 1 아님" 절대 불변식 정합.
 *
 * <p>Spring 무관 (core 모듈). @Component 의존은 app 모듈 구현체에서만.
 */
public interface WikipediaSignalConsumer {

  /**
   * Wikipedia meta signal 소비.
   *
   * @param signals Tier 2 meta signals (factcheckCacheable=false 강제)
   */
  void consume(List<WikipediaMetaSignal> signals);
}
