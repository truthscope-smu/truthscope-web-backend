-- app/src/main/resources/db/migration/V6__add_cascade_metadata.sql
--
-- 결정 근거: .plans/verification-pipeline-cascade-2026-05-24/PLAN.md §1 결정 #4·#5·#6·#13·#14·#17 +
-- DISCUSS §5-5 prerequisite 1 (옵션 A V6 ALTER 채택) + prerequisite 2 (V2 CHECK 무관, 신규 CHECK 추가)
-- + prerequisite 3 (coverage JSONB) + prerequisite 5 (한국어 키워드 별 파일, 본 마이그레이션 무관).
--
-- rev.2 amend Round 1:
--  - CX-1: analysis_sessions.total_score 는 V1 init_schema.sql:20 에 이미 존재. ADD COLUMN 제거, CHECK 만 추가
--  - CX-7: claims 의 attribution 3 컬럼 (NULL 허용 + DEFAULT FALSE) 안전 단계로 0-row guard 제거
--  - R1-5: tier3_reason CHECK 에 `IS NOT NULL` 명시 (PostgreSQL ISO/IEC 9075:2023 §6.34 NULL 함정 차단, V2 패턴 정합)
--
-- ADR-008 rev.1 D3 재현성. V4 = ADR-014 label 물리 DROP 예약 (별 phase).
-- 0-row 전제 guard (V3 패턴 정합).

SET lock_timeout = '5s';

-- ============================================================
-- 0. 0행 전제 검증 (V3 패턴 정합) — verification_results / analysis_sessions.coverage 만
--    rev.2 amend CX-7: claims 의 attribution 3 컬럼은 NULL/DEFAULT 안전 단계로 guard 제거
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM verification_results LIMIT 1) THEN
        RAISE EXCEPTION
            'V6 requires empty verification_results before adding tier3_reason CHECK with NOT NULL clause';
    END IF;
    IF EXISTS (SELECT 1 FROM analysis_sessions WHERE coverage IS NOT NULL AND coverage != '' LIMIT 1) THEN
        RAISE EXCEPTION
            'V6 requires analysis_sessions.coverage to be NULL or empty before VARCHAR(10) → JSONB cast';
    END IF;
    -- rev.2 amend CX-7: claims guard 제거 — attribution 3 컬럼은 NULL 허용 + DEFAULT FALSE 안전
END $$;

-- ============================================================
-- 1. verification_results.tier3_reason 컬럼 + CHECK (rev.2 amend R1-5: IS NOT NULL 명시)
-- ============================================================
ALTER TABLE verification_results
    ADD COLUMN tier3_reason VARCHAR(30) NULL;

-- rev.2 amend R1-5: PostgreSQL ISO/IEC 9075:2023 §6.34 — `NULL IN (...)` evaluates to NULL → CHECK 통과.
-- V2 가 `score IS NOT NULL` 명시로 막은 패턴을 V6 도 따른다.
ALTER TABLE verification_results
    ADD CONSTRAINT ck_verification_results_tier3_reason_consistency
    CHECK (
        (tier = 3 AND tier3_reason IS NOT NULL AND tier3_reason IN ('INSUFFICIENT', 'TIME_SENSITIVE', 'OUT_OF_SCOPE'))
        OR (tier IN (1, 2) AND tier3_reason IS NULL)
    );

-- ============================================================
-- 2. claims 의 attribution 3 컬럼 (BE #76, ADR-020 정합)
--    rev.2 amend CX-7: NULL 허용 + DEFAULT FALSE 안전 단계로 backfill 무관
-- ============================================================
ALTER TABLE claims ADD COLUMN speaker_name VARCHAR(255) NULL;
ALTER TABLE claims ADD COLUMN is_quoted_claim BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claims ADD COLUMN original_context TEXT NULL;

-- ============================================================
-- 3. verification_trace metadata 3 컬럼 (옵션 A 채택, prerequisite 1)
-- ============================================================
ALTER TABLE verification_trace ADD COLUMN prompt_version VARCHAR(50) NULL;
ALTER TABLE verification_trace ADD COLUMN schema_version VARCHAR(50) NULL;
ALTER TABLE verification_trace ADD COLUMN decision_source VARCHAR(30) NULL;

ALTER TABLE verification_trace
    ADD CONSTRAINT ck_verification_trace_decision_source
    CHECK (decision_source IS NULL OR decision_source IN ('GEMINI', 'HEURISTIC_FALLBACK', 'CIRCUIT_BREAKER', 'VALIDATOR_OVERRIDE'));

-- ============================================================
-- 4. analysis_sessions.coverage VARCHAR(10) → JSONB (prerequisite 3)
-- ============================================================
ALTER TABLE analysis_sessions
    ALTER COLUMN coverage TYPE JSONB
    USING CASE
        WHEN coverage IS NULL OR coverage = '' THEN NULL
        ELSE coverage::jsonb
    END;

-- ============================================================
-- 5. analysis_sessions.total_score — V1 init_schema.sql:20 에 이미 존재 (rev.2 amend CX-1)
--    ADD COLUMN 제거, CHECK 제약만 신규 추가.
-- ============================================================
ALTER TABLE analysis_sessions
    ADD CONSTRAINT ck_analysis_sessions_total_score_range
    CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100);
