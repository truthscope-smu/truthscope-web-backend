-- ============================================================================
-- Manual Migration Capsule (Option E, BE #24 정리 PR 동반)
-- ============================================================================
-- Owner: 권석 (PM, KWONSEOK02)
-- Apply window: 운영 배포 전 — 5/12 이후 첫 배포 직전
-- Apply target: Supabase PostgreSQL (운영)
-- Scope: 본 SQL 1회 실행. CI/dev (Testcontainers create-drop)는 자동 처리되어 영향 0.
--
-- Why manual (Abandonment Cost LOCK):
--   Codex 5.5 thread `019e108a` 평가 결과 — Full Flyway 도입은 본 PR scope에서 migration 표준을
--   ADR 없이 lock-in 위험 (rejected score 6/10, 5+ trigger). Option E (manual capsule + follow-up
--   ADR issue)로 안전성 + scope 격리 동시 보존.
--
-- Why idempotent:
--   `IF NOT EXISTS`와 `WHERE source_type IS NULL` 두 가드를 두어 여러 번 실행해도 안전.
--   배포 자동화 / 수동 재실행 / 롤백 후 재시도 어떤 시나리오에서도 데이터 손상 없음.
--
-- Verification (배포 직후 PM 확인) — 기존 2건에서 5건으로 확장 (2026-05-10 Codex/Opus cross-review):
--   (1) schema shape 정확 확인:
--      SELECT table_schema, table_name, column_name, data_type,
--             character_maximum_length, is_nullable, column_default
--      FROM information_schema.columns
--      WHERE table_schema='public' AND table_name='articles' AND column_name='source_type';
--      -- 기대: 1 row, data_type=character varying, max=20, is_nullable=YES
--
--   (2) NULL 잔존 카운트:
--      SELECT COUNT(*) AS null_count FROM public.articles WHERE source_type IS NULL;
--      -- 기대: 0
--
--   (3) NULL row 상세 (있을 경우 데이터 품질 조사 — backfill SQL 위 url IS NOT NULL 가정 위반):
--      SELECT id, url, created_at FROM public.articles
--      WHERE source_type IS NULL ORDER BY created_at DESC LIMIT 20;
--
--   (4) enum domain 위반 (예상치 못한 값):
--      SELECT source_type, COUNT(*) FROM public.articles
--      WHERE source_type IS NOT NULL AND source_type NOT IN ('URL_INPUT', 'TEXT_INPUT')
--      GROUP BY source_type;
--      -- 기대: 0 rows
--
--   (5) URL row backfill 누락:
--      SELECT COUNT(*) AS url_rows_without_source FROM public.articles
--      WHERE url IS NOT NULL AND source_type IS NULL;
--      -- 기대: 0
--
--   (6) Distribution (운영 데이터 sanity check):
--      SELECT source_type, COUNT(*) FROM public.articles GROUP BY source_type;
--      -- 기대: URL_INPUT N건 + (TEXT_INPUT 어댑터 사용 후라면) TEXT_INPUT M건. NULL 0건.
--
-- Rollback policy (HIGH RISK — Codex 5.5 gpt-5.5 thread `019e11bd` + Opus cross-review):
--   ⚠️ DROP COLUMN을 1순위 rollback으로 사용 금지 — TEXT_INPUT 데이터가 들어온 후 DROP하면 영구 손실.
--   1순위 rollback: 앱 롤백 + 컬럼 유지 (additive nullable column이라 기존 앱이 깨질 이유 없음)
--   2순위 (DROP 정말 필요 시) — 다음 prerequisite 모두 통과 후에만:
--     -- ① TEXT_INPUT 데이터 0건 확인:
--     SELECT COUNT(*) FROM public.articles WHERE source_type = 'TEXT_INPUT';  -- 0이어야 함
--     -- ② source_type 박제된 row 백업:
--     CREATE TABLE public.rollback_articles_source_type_20260512 AS
--       SELECT id, source_type FROM public.articles WHERE source_type IS NOT NULL;
--     -- ③ BE lead 승인 후:
--     ALTER TABLE public.articles DROP COLUMN IF EXISTS source_type;
--
-- Follow-up Issue:
--   Issue #52 — "DB migration standard ADR (Flyway vs Supabase CLI vs Liquibase)"
--   Codex 권고: Supabase는 Dashboard 직접 변경 시 migration history 우회 → supabase/migrations 도입 검토.
-- ============================================================================

-- 단일 트랜잭션 — ALTER + UPDATE 원자성 보장 (Codex/Opus 합의 C2 fix)
BEGIN;

-- 운영 트래픽 보호 — lock 대기/장기 실행 차단
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- (1) source_type 컬럼 추가 — 기존 row는 NULL로 시작 (이후 (2)에서 backfill)
ALTER TABLE public.articles
  ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);

-- (1b) schema shape 검증 — 기존 컬럼이 다른 타입/길이/nullable이면 RAISE (Codex C2 강화)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'articles'
      AND column_name = 'source_type'
      AND data_type = 'character varying'
      AND character_maximum_length = 20
      AND is_nullable = 'YES'
  ) THEN
    RAISE EXCEPTION 'articles.source_type schema mismatch (expected varchar(20) nullable)';
  END IF;
END $$;

-- (2) Legacy backfill — URL이 있는 모든 row는 URL_INPUT으로 간주
--     (BE #24 정리 PR 이전 article은 모두 URL 기반 어댑터의 산물이므로 안전)
UPDATE public.articles
SET source_type = 'URL_INPUT'
WHERE source_type IS NULL
  AND url IS NOT NULL;

COMMIT;

-- (3) 통계 갱신 — 대량 update 후 planner 통계 정합 (Codex 권고)
ANALYZE public.articles;

-- (선택, 미적용) NOT NULL 제약 — 다음 schema PR에서 검토:
--   ALTER TABLE public.articles ALTER COLUMN source_type SET NOT NULL;
--   현 단계에서는 보수적으로 NULL 허용 (TEXT_INPUT 어댑터의 첫 row가 들어올 때까지
--   application 레벨에서만 invariant 강제, DB는 이후 PR에서 강화).
