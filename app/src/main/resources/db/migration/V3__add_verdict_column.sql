-- V3 verdict 컬럼 도입 — sprint-2 semantic-cache-correction-layer.
--
-- 결정 근거: .plans/sprint-2-.../DISCUSS.md 11-9(VARCHAR+CHECK), 11-11(label DROP NOT NULL = 1b),
--           11-12(S10 승인), context/decisions/ADR-014-label-enum-migration.md (Accepted).
-- 원칙(ADR-008 Database Migration Standard): V1 동결, 모든 스키마 변경은 신규 V3+ ALTER.
--
-- 박제 결정:
--  - 무결성 기준 이전: V2가 박은 label NOT NULL은 label이 주 저장값일 때 의미가 있었으나,
--    Phase 55 이후 모델(verdict 저장, score 수치, TruthLabel 도출, label deprecated)에서는
--    도메인 무결성을 보호하지 않는다. 무결성은 verdict NOT NULL + 5종 CHECK로 이전한다.
--  - Supabase 데이터 0건 시점이라 ADD COLUMN NOT NULL(DEFAULT 없이)이 무비용.
--    데이터가 쌓이면 기존 row를 채울 DEFAULT 또는 백필이 필요해진다.
--  - label 물리 DROP은 본 V3 미포함 — deprecated 고정 후 후속 V4. correction_registry,
--    freshness 2컬럼, ADR-009 supersede 5컬럼은 V3 미포함(설계 문서에만, DISCUSS 11-15·11-16).

SET lock_timeout = '5s';

-- ============================================================
-- 0. 0행 전제 검증 — verdict는 DEFAULT 없이 NOT NULL로 추가되므로 기존 row가
--    1건이라도 있으면 ADD COLUMN이 NOT NULL 위반으로 실패한다. 전제를 명시적으로
--    강제해 명확한 메시지로 fail-fast 한다(CodeRabbit PR 리뷰 반영). 데이터가
--    있으면 임시 DEFAULT 지정 또는 백필 후 NOT NULL 전환으로 재작성해야 한다.
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM verification_results LIMIT 1) THEN
        RAISE EXCEPTION
            'V3 requires empty verification_results before adding NOT NULL verdict without default/backfill';
    END IF;
END $$;

-- ============================================================
-- 1. verdict 컬럼 추가 — Verdict 5종 enum, @Enumerated(EnumType.STRING) 정합.
--    데이터 0행이라 DEFAULT 없이 NOT NULL 직접 추가 가능.
-- ============================================================
ALTER TABLE verification_results
    ADD COLUMN verdict VARCHAR(30) NOT NULL;

-- ============================================================
-- 2. verdict 허용 값 CHECK — core/.../entity/enums/Verdict.java 5종.
--    Phase 53 D-053-5 LOCK 값. NULL은 1번 NOT NULL이 이미 차단.
-- ============================================================
ALTER TABLE verification_results
    ADD CONSTRAINT ck_verification_results_verdict
    CHECK (verdict IN ('SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT', 'TIME_SENSITIVE', 'OUT_OF_SCOPE'));

-- ============================================================
-- 3. label NOT NULL 해제 — 무결성 기준이 verdict로 이전(DISCUSS 11-11 = 1b).
--    label은 V3부터 nullable + deprecated(신규 의미 부여 금지), 물리 DROP은 V4.
--    DROP NOT NULL은 PostgreSQL 카탈로그 변경이라 테이블 스캔 없음 — 데이터 0행이면 무비용.
-- ============================================================
ALTER TABLE verification_results
    ALTER COLUMN label DROP NOT NULL;
