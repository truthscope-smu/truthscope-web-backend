package com.truthscope.web.adapter.datasource;

/**
 * Wikipedia 문서 vandalism 안정성 상태.
 *
 * <p>domain-logic.md mitigation 정합: - STABLE: 최근 24h 수정 5회 이하 → 사용 가능 - UNSTABLE: 최근 24h 수정 6회 이상 →
 * 결과 차단 + 로그 의무 - UNKNOWN: revision API 호출 실패 시 → 보수적 차단 처리
 */
public enum VandalismStatus {
  STABLE,
  UNSTABLE,
  UNKNOWN
}
