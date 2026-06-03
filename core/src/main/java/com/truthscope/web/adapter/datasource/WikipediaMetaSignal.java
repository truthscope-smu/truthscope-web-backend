package com.truthscope.web.adapter.datasource;

import java.time.Instant;

/**
 * Wikipedia Tier 2 보조 meta-source 파싱 신호 record (H1 amend — parse() 반환형 분리).
 *
 * <p>WikipediaAdapter.parseAsMetaSignal() 반환 전용 타입. DatasourceClaim(Tier 1 경로)과 타입이 달라 컴파일 타임에 Tier
 * 1 evidence 경로로의 직접 전달이 차단된다.
 *
 * <p>summary extract 텍스트는 본 record에 포함하지 않는다. 원 출처 추적 URL(pageUrl) + 메타
 * 정보(title/description/lang/vandalismStatus)만 신호로 전달.
 *
 * @param metaResult Wikipedia Tier 2 계약 객체 (tier=2, disclaimerRequired=true,
 *     factcheckCacheable=false 강제)
 * @param signalAt 신호 생성 시점
 */
public record WikipediaMetaSignal(WikipediaMetaResult metaResult, Instant signalAt) {
  public WikipediaMetaSignal {
    if (metaResult == null) throw new IllegalArgumentException("metaResult는 null 금지");
    if (signalAt == null) throw new IllegalArgumentException("signalAt 필수");
    // tier=2 계약은 WikipediaMetaResult 생성자에서 이미 강제됨
  }
}
