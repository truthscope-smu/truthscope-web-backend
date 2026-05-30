package com.truthscope.web.scoring;

import java.util.List;

/**
 * Tier 2 cascade 단일 claim 결과 계약 (T6a).
 *
 * <p>signal: cascade 검증 신호 (3-Tier 공통). evidence: Tier 2 공식 원문 충실성 스냅샷 목록. Tier 1 / Tier 1' / Tier
 * 3 경로에서는 빈 리스트.
 */
public record ClaimCascadeResult(ClaimVerificationSignal signal, List<EvidenceSnapshot> evidence) {}
