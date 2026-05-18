-- V1 init schema — TruthScope 단일 진실원(엔티티) 기준 최초 스키마.
-- 출처: EntitySchemaExportTest가 Hibernate로 추출한 엔티티 파생 DDL (build/entity-schema.sql), parity 보장.
-- Phase 52 ADR-008(Flyway 표준) 최초 이행. systematic-debugging Phase 4 — root cause(스키마 단일 진실원 부재) 정조준.
--
-- 박제 결정:
--  - enum 컬럼은 @Enumerated(STRING) 대문자 그대로 CHECK (members.role / analysis_sessions.status /
--    claims.importance / user_reactions.type / articles.source_type) → Drift A(대소문자) 해소.
--  - 모든 BaseTimeEntity 테이블에 created_at/updated_at (api_usage_logs 제외) → Drift B 해소.
--  - articles.source_type 존재, domain_type 미포함(엔티티가 진실) → Drift C 해소 및 ERD 문서가 낡음.
--  - verification_results.label은 varchar(30) String 유지 (ADR-014 최소위험 기본값; Verdict enum 연결은 후속 ADR).
--  - timestamp(6) 그대로 보존 (엔티티 LocalDateTime 정합 → ddl-auto=validate 통과 보장. timestamptz 전환은
--    엔티티 타입 변경을 동반하므로 후속 마이그레이션 + ADR로 분리).
--  - 후속 마이그레이션으로 분리(본 V1 미포함, 의도적): factcheck_cache.search_vector + GIN + 트리거(Tier1 한국어
--    검색 기능 도입 시), RLS 정책(ERD 5절), ERD 4절의 NOT NULL/DEFAULT 강화. 미사용 인프라 선반영 금지 원칙.

create table analysis_sessions (
    tier1_count smallint,
    tier2_count smallint,
    tier3_count smallint,
    total_score smallint,
    completed_at timestamp(6),
    created_at timestamp(6),
    requested_at timestamp(6),
    updated_at timestamp(6),
    coverage varchar(10),
    id uuid not null,
    member_id uuid,
    status varchar(20) check (status in ('PENDING','EXTRACTING','ANALYZING','COMPLETED','FAILED')),
    error_message TEXT,
    primary key (id)
);

create table api_usage_logs (
    request_count integer,
    token_count integer,
    usage_date date,
    id uuid not null,
    member_id uuid,
    provider varchar(30),
    model varchar(50),
    primary key (id)
);

create table articles (
    created_at timestamp(6),
    extracted_at timestamp(6),
    updated_at timestamp(6),
    lang varchar(10),
    id uuid not null,
    session_id uuid unique,
    source_type varchar(20) check (source_type in ('URL_INPUT','TEXT_INPUT')),
    title varchar(500),
    url varchar(2048),
    body TEXT,
    domain varchar(255),
    primary key (id)
);

create table claims (
    sort_order smallint,
    created_at timestamp(6),
    updated_at timestamp(6),
    importance varchar(10) check (importance in ('HIGH','MEDIUM','LOW')),
    article_id uuid,
    id uuid not null,
    text TEXT,
    primary key (id)
);

create table factcheck_cache (
    collected_at timestamp(6),
    created_at timestamp(6),
    expires_at timestamp(6),
    updated_at timestamp(6),
    language varchar(10),
    id uuid not null,
    rating varchar(50),
    source_org varchar(100),
    original_url varchar(2048),
    claim_text TEXT,
    primary key (id)
);

create table members (
    created_at timestamp(6),
    updated_at timestamp(6),
    id uuid not null,
    role varchar(20) check (role in ('USER','ADMIN')),
    nickname varchar(50),
    email varchar(255) unique,
    primary key (id)
);

create table user_reactions (
    created_at timestamp(6),
    updated_at timestamp(6),
    type varchar(10) check (type in ('LIKE','DISLIKE','REPORT')),
    article_id uuid,
    id uuid not null,
    member_id uuid,
    reason varchar(500),
    primary key (id),
    constraint uk_user_reactions_article_member_type unique (article_id, member_id, type)
);

create table verification_results (
    score smallint,
    tier smallint,
    created_at timestamp(6),
    updated_at timestamp(6),
    verified_at timestamp(6),
    claim_id uuid unique,
    id uuid not null,
    label varchar(30),
    disclaimer TEXT,
    reason TEXT,
    primary key (id)
);

create table verify_sources (
    created_at timestamp(6),
    updated_at timestamp(6),
    stance varchar(10),
    id uuid not null,
    result_id uuid,
    rating varchar(50),
    publisher varchar(200),
    title varchar(500),
    url varchar(2048),
    summary TEXT,
    primary key (id)
);

alter table analysis_sessions
    add constraint fk_analysis_sessions_member
    foreign key (member_id) references members;

alter table api_usage_logs
    add constraint fk_api_usage_logs_member
    foreign key (member_id) references members;

alter table articles
    add constraint fk_articles_session
    foreign key (session_id) references analysis_sessions;

alter table claims
    add constraint fk_claims_article
    foreign key (article_id) references articles;

alter table user_reactions
    add constraint fk_user_reactions_article
    foreign key (article_id) references articles;

alter table user_reactions
    add constraint fk_user_reactions_member
    foreign key (member_id) references members;

alter table verification_results
    add constraint fk_verification_results_claim
    foreign key (claim_id) references claims;

alter table verify_sources
    add constraint fk_verify_sources_result
    foreign key (result_id) references verification_results;
