package com.truthscope.web.entity;

/**
 * 기사 도메인 객체의 출처 타입.
 *
 * <p>같은 {@link Article} aggregate라도 어느 입구로 들어왔는지에 따라 invariant가 달라진다. 이 enum이 그 진입 경로를 모델에 명시적으로
 * 박제한다 (DDD always-valid 모델 + sourceType polymorphic invariant 패턴).
 *
 * <ul>
 *   <li>{@link #URL_INPUT} — 외부 뉴스 URL을 입력받아 fetch하여 만든 기사. {@code url} 필수, http(s) 스킴 필수.
 *   <li>{@link #TEXT_INPUT} — 사용자가 본문을 직접 붙여넣어 만든 기사. {@code url} null 허용 (외부 출처가 없음).
 * </ul>
 *
 * <p>새 출처(예: 파일 업로드, RSS 구독)가 추가될 때 본 enum에 값 추가 + {@link Article}에 대응 팩토리 추가하는 패턴.
 */
public enum ArticleSource {
  URL_INPUT,
  TEXT_INPUT
}
