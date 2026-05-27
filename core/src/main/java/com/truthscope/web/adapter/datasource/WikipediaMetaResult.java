package com.truthscope.web.adapter.datasource;

/**
 * Wikipedia Tier 2 보조 meta-source 조회 결과 도메인 record.
 *
 * <p>domain-logic.md Wikipedia placement 룰 정합: - tier는 항상 2 (Tier 1 evidence 사용 금지) -
 * disclaimerRequired는 항상 true (Tier 2 disclaimer 없이 표시 금지) - factcheckCacheable은 항상 false
 * (factcheck_cache 저장 금지)
 *
 * <p>호출부가 이 record를 사용하려면 tier=2 + disclaimerRequired=true를 명시적으로 인지해야 한다. 직접 score에 합산하지 않는다 (D11
 * 결정 — score 밖 신호).
 *
 * <p>[H1 codex Round 2 미해소 amend — 옵션 A] extract 텍스트 필드 완전 제거. lateral reading 원칙(Wikipedia 본문 ≠
 * evidence) 컴파일 타임 강제. signal.metaResult().extract() 접근 자체가 컴파일 에러 — 증거/캐시 복사 경로 원천 차단. extract 필드
 * 향후 부활 금지 (부활 시 새 DISCUSS + ADR 필요).
 *
 * @param title Wikipedia 문서 제목
 * @param description 단문 설명 (예: "대한민국의 정치인")
 * @param pageUrl 원문 URL (footnote 추적 트리거용)
 * @param lang 언어 코드 ("ko" 또는 "en")
 * @param vandalismStatus 안정성 상태 (STABLE/UNSTABLE/UNKNOWN)
 * @param tier 항상 (short)2 — Tier 1 아님 강제
 * @param disclaimerRequired 항상 true — UI disclaimer 의무
 * @param factcheckCacheable 항상 false — factcheck_cache 저장 금지
 */
public record WikipediaMetaResult(
    String title,
    String description,
    String pageUrl,
    String lang,
    VandalismStatus vandalismStatus,
    short tier,
    boolean disclaimerRequired,
    boolean factcheckCacheable) {

  public WikipediaMetaResult {
    if (tier != 2) throw new IllegalArgumentException("WikipediaMetaResult.tier는 반드시 2이어야 한다");
    if (!disclaimerRequired)
      throw new IllegalArgumentException("WikipediaMetaResult.disclaimerRequired는 반드시 true이어야 한다");
    if (factcheckCacheable)
      throw new IllegalArgumentException(
          "WikipediaMetaResult.factcheckCacheable은 반드시 false이어야 한다 (factcheck_cache 저장 금지)");
  }

  /**
   * 정적 팩토리 — 항상 tier=2, disclaimerRequired=true, factcheckCacheable=false 강제. extract 파라미터 제거됨 (H1
   * codex Round 2 amend — lateral reading 원칙 컴파일 타임 강제).
   */
  public static WikipediaMetaResult of(
      String title,
      String description,
      String pageUrl,
      String lang,
      VandalismStatus vandalismStatus) {
    return new WikipediaMetaResult(
        title, description, pageUrl, lang, vandalismStatus, (short) 2, true, false);
  }
}
