-- Phase 66b (L-3 해소): factcheck_cache.search_vector 컬럼.
-- V1__init_schema.sql:13에서 "후속 마이그레이션으로 분리(의도적)"로 deferred됐던 컬럼.
-- FactcheckCacheRepository.searchByText()가 `search_vector @@ plainto_tsquery('simple', :query)`로 의존.
-- 2026-05-31 Supabase 실측: search_vector ABSENT (create 분기). precondition은 이미 수동 존재 시
-- 다른 정의 충돌을 막는 안전장치 (codex plan-review Round 2 조건 2).
DO $$
DECLARE
  col_type text;
  col_generated char;
  gen_expr text;
BEGIN
  SELECT a.atttypid::regtype::text, a.attgenerated, pg_get_expr(d.adbin, d.adrelid)
    INTO col_type, col_generated, gen_expr
    FROM pg_attribute a
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attrelid = 'public.factcheck_cache'::regclass
      AND a.attname = 'search_vector'
      AND a.attnum > 0
      AND NOT a.attisdropped;

  IF col_type IS NOT NULL THEN
    -- 이미 존재: type/generated/expression 정합 검증. 불일치 시 명시적 실패(수동 repair 유도).
    IF col_type <> 'tsvector' OR col_generated <> 's' OR gen_expr IS NULL OR gen_expr NOT LIKE '%to_tsvector(%' THEN
      RAISE EXCEPTION 'search_vector exists with incompatible definition (type=%, generated=%, expr=%) -- manual repair required before V8',
        col_type, col_generated, gen_expr;
    END IF;
  ELSE
    -- 부재: GENERATED STORED tsvector 컬럼 생성 (트리거 불필요).
    ALTER TABLE factcheck_cache
      ADD COLUMN search_vector tsvector
      GENERATED ALWAYS AS (to_tsvector('simple', coalesce(claim_text, ''))) STORED;
  END IF;

  -- GIN 인덱스 (idempotent).
  CREATE INDEX IF NOT EXISTS idx_factcheck_cache_search_vector
    ON factcheck_cache USING GIN (search_vector);
END $$;
