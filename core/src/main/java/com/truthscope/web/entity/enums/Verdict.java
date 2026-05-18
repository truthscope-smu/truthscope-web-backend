package com.truthscope.web.entity.enums;

/**
 * Claim 검증 판정값 (5단계).
 *
 * <p>Phase 53 결정 D-053-5에서 LOCK된 고정 값이다. 정보통신망법 70조(명예훼손) 및 AI 기본법 2026.1 제20조 대응 논거에 따라, 자동 검증
 * 시스템은 단정적 참 또는 거짓 외에 "판단 불가" 계열 값을 명시적으로 가져야 한다 (도메인 원칙 "모르면 모른다").
 *
 * <ul>
 *   <li>{@link #SUPPORTED} — 출처가 Claim을 뒷받침함
 *   <li>{@link #CONTRADICTED} — 출처가 Claim과 배치됨
 *   <li>{@link #INSUFFICIENT} — 근거 부족으로 판정 불가 (Tier 3, score = NULL)
 *   <li>{@link #TIME_SENSITIVE} — 시점 의존 사실 (재검증 필요, supersede 대상)
 *   <li>{@link #OUT_OF_SCOPE} — 검증 범위 밖 (의견 또는 예측, ADR-007 스코프 경계 정합)
 * </ul>
 *
 * <p>이 enum이 {@code verification_results.label}(현재 VARCHAR(30) String)을 대체할지, 별도 컬럼으로 신설될지는
 * ADR-014(라벨 enum 마이그레이션 — 조사 대기)에서 확정한다. 본 클래스는 값 정의만 박제하며 DB 매핑 전략은 ADR-014 확정 전까지 사용처에 연결하지 않는다.
 *
 * <p>Entity 필드 연결 시 backend-conventions 규칙대로 {@code @Enumerated(EnumType.STRING)} 의무 (ORDINAL 금지).
 */
public enum Verdict {
  SUPPORTED,
  CONTRADICTED,
  INSUFFICIENT,
  TIME_SENSITIVE,
  OUT_OF_SCOPE
}
