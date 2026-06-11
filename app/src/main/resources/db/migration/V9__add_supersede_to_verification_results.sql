-- Phase 72 Wave1-A: verification_results 테이블 supersede 체인 컬럼 추가 (ADR-009 rev.3 정합)
-- superseded_at, superseded_by_result_id, supersede_reason, original_result_id, last_confirmed_at 5컬럼 추가.
-- 기존 unique(claim_id) 단일 제약을 partial unique 인덱스(superseded_at IS NULL)로 교체하여
-- 동일 claim에 대한 이력 체인(supersede 패턴)을 허용한다.
-- 타입 근거: TIMESTAMP(6) = V1 표준(엔티티 LocalDateTime 정합, TIMESTAMPTZ 금지).
-- CHECK 값 대문자 = V3 verdict, V6 tier3_reason CHECK 표준 정합.

SET LOCAL lock_timeout = '5s';

ALTER TABLE verification_results ADD COLUMN superseded_at TIMESTAMP(6);
ALTER TABLE verification_results ADD COLUMN superseded_by_result_id UUID REFERENCES verification_results(id);
ALTER TABLE verification_results ADD COLUMN supersede_reason VARCHAR(30)
    CHECK (supersede_reason IN ('LABEL_CHANGED','SCORE_DRIFT','URL_REPLACEMENT',
                                'TIER_CHANGED','USER_REPORT','SCHEDULED_REVERIFY'));

-- supersede 상태 일관성: 마킹된 행은 반드시 사유를 갖는다 (둘 다 NULL이거나 둘 다 NOT NULL)
ALTER TABLE verification_results ADD CONSTRAINT chk_vr_supersede_pair
    CHECK ((superseded_at IS NULL) = (supersede_reason IS NULL));
ALTER TABLE verification_results ADD COLUMN original_result_id UUID REFERENCES verification_results(id);
ALTER TABLE verification_results ADD COLUMN last_confirmed_at TIMESTAMP(6);

-- V1의 inline unique(claim_id uuid unique) 자동명은 verification_results_claim_id_key가 표준이나
-- 환경 drift 대비 동적으로 찾아 drop한다.
DO $$
DECLARE uq_name text;
BEGIN
  SELECT conname INTO uq_name FROM pg_constraint
   WHERE conrelid = 'verification_results'::regclass AND contype = 'u'
     AND conkey = (SELECT array_agg(attnum) FROM pg_attribute
                    WHERE attrelid = 'verification_results'::regclass AND attname = 'claim_id');
  IF uq_name IS NULL THEN RAISE EXCEPTION 'claim_id unique 제약을 찾지 못함 — 스키마 drift 확인 필요'; END IF;
  EXECUTE format('ALTER TABLE verification_results DROP CONSTRAINT %I', uq_name);
END $$;

CREATE UNIQUE INDEX uq_vr_claim_current ON verification_results (claim_id)
    WHERE superseded_at IS NULL;
CREATE INDEX idx_vr_claim_valid ON verification_results (claim_id, verified_at DESC)
    WHERE superseded_at IS NULL;
