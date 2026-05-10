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
-- Verification (배포 직후 PM 확인):
--   1) 컬럼 존재 확인:
--      SELECT column_name, data_type, character_maximum_length
--      FROM information_schema.columns
--      WHERE table_name = 'articles' AND column_name = 'source_type';
--      -- 기대: 1 row (data_type=character varying, max=20)
--
--   2) Backfill 결과 확인 (legacy URL articles 모두 채워졌는지):
--      SELECT source_type, COUNT(*) AS cnt
--      FROM public.articles
--      GROUP BY source_type;
--      -- 기대: URL_INPUT N건 + (텍스트 입력 사용 후라면) TEXT_INPUT M건. NULL 0건.
--
-- Follow-up Issue:
--   W4.6-b에서 생성 예정 — "DB migration standard ADR (Flyway vs Supabase CLI vs Liquibase)"
--   본 issue 머지 전까지 추가 schema-changing PR 금지 (lock rule, HANDOFF §3 D-9).
-- ============================================================================

-- (1) source_type 컬럼 추가 — 기존 row는 NULL로 시작 (이후 (2)에서 backfill)
ALTER TABLE public.articles
  ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);

-- (2) Legacy backfill — URL이 있는 모든 row는 URL_INPUT으로 간주
--     (BE #24 정리 PR 이전 article은 모두 URL 기반 어댑터의 산물이므로 안전)
UPDATE public.articles
SET source_type = 'URL_INPUT'
WHERE source_type IS NULL
  AND url IS NOT NULL;

-- (선택, 미적용) NOT NULL 제약 — 다음 schema PR에서 검토:
--   ALTER TABLE public.articles ALTER COLUMN source_type SET NOT NULL;
--   현 단계에서는 보수적으로 NULL 허용 (TEXT_INPUT 어댑터의 첫 row가 들어올 때까지
--   application 레벨에서만 invariant 강제, DB는 이후 PR에서 강화).
