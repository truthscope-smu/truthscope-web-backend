-- V5 verification_trace + data_source_snapshots 스키마 추가
-- 결정 근거: .plans/be21-.../DISCUSS.md Q1/Q2/Q3 + Round 1 plan-review-deep amend (R1-H2 timestamp 통일, R1-CX9 UNIQUE 의미 축소, R1-CX11 constraint name 명시).
-- ADR-008 rev.1 D3 재현성 원칙 skeleton. V4 = ADR-014 label DROP 예약 (별 phase).
-- fallback_stage는 Sprint 3 V6 ALTER 분리 (DISCUSS Q3 A결정).
-- 시간 타입: V1 기존 9 테이블 패턴 정합으로 timestamp(6) 채택 (Round 1 H-2 정정).

SET lock_timeout = '5s';

-- ============================================================
-- 1. verification_trace 테이블
--    Tier 1 Google FC 호출: prompt_git_sha / prompt_hash / model_version NULL 허용
--    duration_ms >= 0 CHECK (음수 방어). constraint name 명시로 회귀 ABC C deterministic FAIL 보장.
-- ============================================================
CREATE TABLE verification_trace (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    verification_result_id UUID         NOT NULL,
    tier                   SMALLINT     NOT NULL,
    adapter_name           TEXT         NOT NULL,
    prompt_git_sha         TEXT         NULL,
    prompt_hash            TEXT         NULL,
    model_version          TEXT         NULL,
    request_body           JSONB        NOT NULL,
    response_body          JSONB        NOT NULL,
    duration_ms            INTEGER      NOT NULL,
    created_at             TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_verification_trace_result
        FOREIGN KEY (verification_result_id) REFERENCES verification_results(id),
    CONSTRAINT ck_verification_trace_tier
        CHECK (tier IN (1, 2, 3)),
    CONSTRAINT ck_verification_trace_duration_nonneg
        CHECK (duration_ms >= 0)
);

CREATE INDEX idx_trace_result  ON verification_trace(verification_result_id);
CREATE INDEX idx_trace_created ON verification_trace(created_at DESC);

-- ============================================================
-- 2. data_source_snapshots 테이블 (DISCUSS §2.7 정본 — query-response snapshot)
--    UNIQUE (adapter_name, query_hash, retrieved_at) — 동일 retrieved_at 동시 INSERT만 차단.
--    PLAN.md 작성 시 "idempotent INSERT 보장" 과장이었음 — retrieved_at는 @CreationTimestamp로
--    JPA save 마다 다른 값 단계로 다른 시각의 동일 query는 충돌 안 함 (R1-CX9 정정).
--    재현 가능한 idempotent 의미는 Sprint 3 ON CONFLICT DO NOTHING + natural key 재검토 시 확정.
-- ============================================================
CREATE TABLE data_source_snapshots (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter_name   TEXT         NOT NULL,
    query_hash     TEXT         NOT NULL,
    source_version TEXT         NULL,
    response_body  JSONB        NOT NULL,
    retrieved_at   TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_data_source_snapshots_adapter_query_retrieved
        UNIQUE (adapter_name, query_hash, retrieved_at)
);

CREATE INDEX idx_snapshot_lookup ON data_source_snapshots(adapter_name, query_hash);
