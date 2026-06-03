-- V2 hardening — TruthScope 데이터 무결성 제약 복원.
--
-- 출처: docs/md/10_erd.md 0-2 후속 마이그레이션 backlog + 4절 원안 DDL 의도.
-- 결정 근거: docs/architecture/decisions/ADR-009-v2-integrity-hardening.md (Phase 54 D8).
-- 원칙(ADR-008 Database Migration Standard): V1__init_schema.sql 동결, 모든 스키마 변경은 신규 V2+ ALTER.
--
-- 박제 결정:
--  - verification_results 복합 불변식: domain-logic "모르면 모른다" — Tier 3은 score NULL,
--    Tier 1/2는 score 0~100 비-NULL. score IS NOT NULL 항으로 SQL 3치 논리 구멍을 닫는다.
--  - NOT NULL은 (a) 현 코드 경로가 항상 채우는 컬럼, (b) INSERT 경로가 아직 없어 회귀 위험 0인
--    컬럼에만 적용. articles.url/lang은 source_type=TEXT_INPUT 설계상 NULL 가능 → 의도적 제외.
--  - 후속(본 V2 미포함): factcheck_cache.search_vector(V3 검색), RLS(V4 보안),
--    analysis_sessions tier count / api_usage_logs 카운터의 NOT NULL+DEFAULT 0 쌍(엔티티 필드
--    nullable + JParla explicit-NULL INSERT 상호작용 → 엔티티 변경 동반 필요).
--
-- Supabase 데이터 0건 시점이라 NOT NULL/CHECK 추가가 무비용. 데이터가 쌓인 뒤엔 불량 row로 적용이 막힌다.

SET lock_timeout = '5s';

-- ============================================================
-- 1. verification_results 복합 불변식 (Phase 54 D8 — "모르면 모른다")
--    Tier 3 = score 반드시 NULL(점수 부여 금지), Tier 1/2 = score 반드시 0~100 비-NULL.
--    score IS NOT NULL 항이 없으면 tier IN (1,2) + score=NULL이 3치 논리로 CHECK를 통과해버린다.
-- ============================================================
ALTER TABLE verification_results
    ADD CONSTRAINT ck_verification_results_tier_score
    CHECK (
        (tier = 3 AND score IS NULL)
        OR (tier IN (1, 2) AND score IS NOT NULL AND score BETWEEN 0 AND 100)
    );

-- ============================================================
-- 2. verify_sources.stance 허용 값 (Tier 2 AI 교차검증 판정).
--    Tier 1에서는 stance가 NULL이며, NULL은 CHECK가 3치 논리로 자동 허용한다.
-- ============================================================
ALTER TABLE verify_sources
    ADD CONSTRAINT ck_verify_sources_stance
    CHECK (stance IN ('supports', 'refutes', 'neutral'));

-- ============================================================
-- 3. NOT NULL — 비즈니스 필수 컬럼 + 감사 시각(created_at/updated_at).
-- ============================================================
ALTER TABLE members ALTER COLUMN email SET NOT NULL;
ALTER TABLE members ALTER COLUMN nickname SET NOT NULL;
ALTER TABLE members ALTER COLUMN role SET NOT NULL;
ALTER TABLE members ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE members ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE analysis_sessions ALTER COLUMN status SET NOT NULL;
ALTER TABLE analysis_sessions ALTER COLUMN requested_at SET NOT NULL;
ALTER TABLE analysis_sessions ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE analysis_sessions ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE articles ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE articles ALTER COLUMN title SET NOT NULL;
ALTER TABLE articles ALTER COLUMN body SET NOT NULL;
ALTER TABLE articles ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE articles ALTER COLUMN extracted_at SET NOT NULL;
ALTER TABLE articles ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE articles ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE claims ALTER COLUMN article_id SET NOT NULL;
ALTER TABLE claims ALTER COLUMN text SET NOT NULL;
ALTER TABLE claims ALTER COLUMN importance SET NOT NULL;
ALTER TABLE claims ALTER COLUMN sort_order SET NOT NULL;
ALTER TABLE claims ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE claims ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE verification_results ALTER COLUMN tier SET NOT NULL;
ALTER TABLE verification_results ALTER COLUMN label SET NOT NULL;
ALTER TABLE verification_results ALTER COLUMN reason SET NOT NULL;
ALTER TABLE verification_results ALTER COLUMN verified_at SET NOT NULL;
ALTER TABLE verification_results ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE verification_results ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE verify_sources ALTER COLUMN result_id SET NOT NULL;
ALTER TABLE verify_sources ALTER COLUMN title SET NOT NULL;
ALTER TABLE verify_sources ALTER COLUMN publisher SET NOT NULL;
ALTER TABLE verify_sources ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE verify_sources ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE user_reactions ALTER COLUMN article_id SET NOT NULL;
ALTER TABLE user_reactions ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE user_reactions ALTER COLUMN type SET NOT NULL;
ALTER TABLE user_reactions ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE user_reactions ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE factcheck_cache ALTER COLUMN claim_text SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN source_org SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN rating SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN original_url SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN language SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN collected_at SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE factcheck_cache ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE api_usage_logs ALTER COLUMN provider SET NOT NULL;
ALTER TABLE api_usage_logs ALTER COLUMN usage_date SET NOT NULL;

-- ============================================================
-- 4. DEFAULT — id(gen_random_uuid), 감사 시각(now()).
--    JPA는 값을 명시 INSERT하므로 DEFAULT는 raw SQL/운영 작업용 방어막이다.
--    members.id는 @GeneratedValue 미사용(Supabase Auth uid) → DEFAULT 미부여.
-- ============================================================
ALTER TABLE analysis_sessions    ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE articles             ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE claims               ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE verification_results ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE verify_sources       ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE user_reactions       ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE factcheck_cache      ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE api_usage_logs       ALTER COLUMN id SET DEFAULT gen_random_uuid();

ALTER TABLE members              ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE members              ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE analysis_sessions    ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE analysis_sessions    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE articles             ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE articles             ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE claims               ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE claims               ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE verification_results ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE verification_results ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE verify_sources       ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE verify_sources       ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE user_reactions       ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE user_reactions       ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE factcheck_cache      ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE factcheck_cache      ALTER COLUMN updated_at SET DEFAULT now();

-- ============================================================
-- 5. FK ON DELETE 의도 복원 (10_erd.md 4절 원안). V1은 plain FK였다.
--    분석 그래프(session -> article -> claim -> result -> source)는 CASCADE,
--    member 참조는 SET NULL(비로그인 분석 허용) 또는 CASCADE(member 소유 반응).
-- ============================================================
ALTER TABLE analysis_sessions DROP CONSTRAINT fk_analysis_sessions_member;
ALTER TABLE analysis_sessions ADD CONSTRAINT fk_analysis_sessions_member
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE SET NULL;

ALTER TABLE api_usage_logs DROP CONSTRAINT fk_api_usage_logs_member;
ALTER TABLE api_usage_logs ADD CONSTRAINT fk_api_usage_logs_member
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE SET NULL;

ALTER TABLE articles DROP CONSTRAINT fk_articles_session;
ALTER TABLE articles ADD CONSTRAINT fk_articles_session
    FOREIGN KEY (session_id) REFERENCES analysis_sessions (id) ON DELETE CASCADE;

ALTER TABLE claims DROP CONSTRAINT fk_claims_article;
ALTER TABLE claims ADD CONSTRAINT fk_claims_article
    FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE;

ALTER TABLE verification_results DROP CONSTRAINT fk_verification_results_claim;
ALTER TABLE verification_results ADD CONSTRAINT fk_verification_results_claim
    FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE;

ALTER TABLE verify_sources DROP CONSTRAINT fk_verify_sources_result;
ALTER TABLE verify_sources ADD CONSTRAINT fk_verify_sources_result
    FOREIGN KEY (result_id) REFERENCES verification_results (id) ON DELETE CASCADE;

ALTER TABLE user_reactions DROP CONSTRAINT fk_user_reactions_article;
ALTER TABLE user_reactions ADD CONSTRAINT fk_user_reactions_article
    FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE;

ALTER TABLE user_reactions DROP CONSTRAINT fk_user_reactions_member;
ALTER TABLE user_reactions ADD CONSTRAINT fk_user_reactions_member
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE;

-- ============================================================
-- 6. 일반 인덱스 복원 (10_erd.md 4절 원안). V1은 PK/UNIQUE 인덱스만 두었다.
-- ============================================================
CREATE INDEX idx_sessions_member     ON analysis_sessions (member_id);
CREATE INDEX idx_sessions_status     ON analysis_sessions (status);
CREATE INDEX idx_sessions_requested  ON analysis_sessions (requested_at DESC);
CREATE INDEX idx_articles_url        ON articles (url);
CREATE INDEX idx_articles_domain     ON articles (domain);
CREATE INDEX idx_claims_article      ON claims (article_id, sort_order);
CREATE INDEX idx_vresults_tier       ON verification_results (tier);
CREATE INDEX idx_usage_provider_date ON api_usage_logs (provider, usage_date);
CREATE INDEX idx_usage_member_date   ON api_usage_logs (member_id, usage_date);
