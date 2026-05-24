package com.truthscope.web.entity.enums;

/**
 * Cascade decision 의 source 분류 (verification_trace.decision_source 컬럼 매핑). V6 CHECK 와 컴파일 시점 타입 안전
 * 이중 강제.
 *
 * <ul>
 *   <li>{@link #GEMINI} — Gemini API 가 결정한 source
 *   <li>{@link #HEURISTIC_FALLBACK} — 휴리스틱 폴백이 결정한 source
 *   <li>{@link #CIRCUIT_BREAKER} — Circuit Breaker 가 개입한 source
 *   <li>{@link #VALIDATOR_OVERRIDE} — 검증기가 수동 override 한 source
 * </ul>
 *
 * <p>Entity 필드 연결 시 {@code @Enumerated(EnumType.STRING)} 의무 (ORDINAL 금지).
 */
public enum DecisionSource {
  GEMINI,
  HEURISTIC_FALLBACK,
  CIRCUIT_BREAKER,
  VALIDATOR_OVERRIDE
}
