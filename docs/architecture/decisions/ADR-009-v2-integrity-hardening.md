---
number: "009"
title: "V2 Data Integrity Hardening Migration"
status: "Accepted"
date: "2026-05-22"
authors: ["KWONSEOK02"]
---

# ADR-009 — V2 Data Integrity Hardening Migration

## Context

Phase 52(ADR-008)가 Flyway를 마이그레이션 표준으로 채택하고 `V1__init_schema.sql`을 작성·Supabase 적용·체크섬 동결했다. V1은 의도적으로 엔티티 파생 DDL(컬럼·타입·PK·UNIQUE·plain FK)만 담았고, 도메인 무결성 제약은 후속으로 미뤘다 — `docs/md/10_erd.md` 0-2 backlog가 그 미뤄진 항목을 박제한다.

미뤄진 핵심은 `verification_results`의 3-Tier Cascade 불변식이다. `.claude/rules/domain-logic.md`의 "모르면 모른다" 원칙상 Tier 3(검증 불가)은 score가 반드시 NULL이어야 하고 Tier 1/2는 0~100 점수를 가져야 한다. V1에는 이 제약이 없어 `tier=3 + score=50`처럼 도메인을 위반하는 row가 DB 레벨에서 차단되지 않는다. Phase 54 cross-review(codex gpt-5.5 thread `019e39df`)가 이 누락을 High로 판정하고 "후속 backlog가 아니라 V1 직후 즉시 V2, 6/8 제출 blocker"로 격상했다.

제약 조건:
- **V1 동결**: `flyway_schema_history` v1에 체크섬이 기록돼 V1을 1바이트라도 수정하면 다음 부팅이 checksum mismatch로 실패한다. 모든 스키마 변경은 신규 `V2+` ALTER여야 한다(ADR-008 표준).
- **데이터 0건 시점**: Supabase는 V1로 막 생성돼 데이터가 없다. NOT NULL/CHECK 추가가 지금은 무비용이지만, 앱이 데이터를 쓰기 시작한 뒤엔 불량 row가 V2 적용을 막는다.
- **회귀 0**: 기존 131개 테스트가 green을 유지해야 한다.

관련 ADR:
- ADR-008(Database Migration Standard) — 본 ADR의 직계 선행. V1 동결 + 신규 V*.sql ALTER 패턴.
- ADR-006(LLM Behavior Gates) — 무관.

## Decision

We will add `V2__hardening.sql` — V1을 수정하지 않는 ALTER 전용 마이그레이션 — 으로 데이터 무결성 제약을 복원한다.

### §1 verification_results 복합 불변식

다음 CHECK 제약을 추가한다:

```sql
CHECK (
    (tier = 3 AND score IS NULL)
    OR (tier IN (1, 2) AND score IS NOT NULL AND score BETWEEN 0 AND 100)
)
```

Phase 54 D8의 원안 표현은 `(tier=3 AND score IS NULL) OR (tier IN (1,2) AND score BETWEEN 0 AND 100)`였다. 여기에 **`score IS NOT NULL` 항을 추가**한다. 이는 D8 결정을 바꾸는 것이 아니라 D8의 의도를 SQL로 정확히 인코딩하는 것이다 — 원안 표현은 SQL 3치 논리(three-valued logic) 구멍이 있다. `tier=1, score=NULL`이면 첫 항은 FALSE, 둘째 항은 `TRUE AND NULL = NULL`, `FALSE OR NULL = NULL`이 되어 CHECK가 통과한다(CHECK는 FALSE일 때만 거부). `score IS NOT NULL` 항이 둘째 항을 FALSE로 만들어 이 구멍을 닫는다. `tier`에 NOT NULL을 함께 걸어 `tier=NULL`로 전체가 NULL이 되는 경로도 차단한다.

### §2 verify_sources.stance 허용 값

`CHECK (stance IN ('supports', 'refutes', 'neutral'))`를 추가한다. Tier 1 출처는 stance가 NULL이며, `NULL IN (...)`은 NULL로 평가돼 CHECK가 자동 허용한다 — 별도 NULL 허용 절이 불필요하다. ERD 원안의 CHECK 의도(Medium)를 최소 위험으로 복원하며, `SourceStance` enum 승격은 엔티티 타입 변경을 동반하므로 별도 트랙으로 남긴다.

### §3 NOT NULL 적용 범위

`verification_results`는 `tier/label/reason/verified_at`에 NOT NULL을 건다(Phase 54 HANDOFF 명시 4컬럼). 나머지 테이블은 다음 두 부류에만 NOT NULL을 적용한다:

- 현 코드 경로가 항상 채우는 컬럼 — `analysis_sessions`(status/requested_at), `articles`(session_id/title/body/source_type/extracted_at).
- INSERT 경로가 아직 없어 회귀 위험이 0인 컬럼 — `members`, `claims`, `verification_results`, `verify_sources`, `user_reactions`, `factcheck_cache`, `api_usage_logs`의 ERD 필수 컬럼.

`created_at`와 `updated_at`은 두 컬럼 모두 NOT NULL로 건다. 0-2 backlog의 "생성시각"을 감사 시각 쌍으로 해석한다 — JPA Auditing(`@CreatedDate`/`@LastModifiedDate`)이 INSERT 시 두 값을 모두 채우며, ERD 4절 원안도 `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`로 쌍을 NOT NULL로 둔다. created_at만 NOT NULL로 두면 일관성이 깨진다.

### §4 DEFAULT

`id`(8개 `@GeneratedValue` 테이블)에 `gen_random_uuid()`, `created_at`/`updated_at`에 `now()`를 DEFAULT로 건다. JPA는 값을 명시 INSERT하므로 이 DEFAULT는 raw SQL·운영 manual SQL용 방어막이며 JPA 경로에는 영향이 없다. `members.id`는 `@GeneratedValue`를 쓰지 않으므로(Supabase Auth uid를 외부에서 주입) DEFAULT를 부여하지 않는다.

### §5 FK ON DELETE 의도 복원

V1은 plain FK 8개를 만들었다. ERD 4절 원안 의도대로 분석 그래프(`session -> article -> claim -> result -> source`)는 `ON DELETE CASCADE`, member 참조는 `ON DELETE SET NULL`(비로그인 분석 허용) 또는 `CASCADE`(member 소유 반응)로 DROP 후 재생성한다.

### §6 일반 인덱스 복원

ERD 4절 원안의 일반 인덱스 9개(`idx_sessions_*` 3, `idx_articles_*` 2, `idx_claims_article`, `idx_vresults_tier`, `idx_usage_*` 2)를 복원한다. `CONCURRENTLY`는 쓰지 않는다 — Flyway가 마이그레이션을 단일 트랜잭션으로 감싸며, 데이터 0건이라 일반 `CREATE INDEX`로 충분하다.

## Status

`Accepted` (2026-05-22)

cross-review 2종 통과 — codex gpt-5.5 thread `019e4f30` PROCEED(High 0건), Claude `code-reviewer` Agent 8-lens PROCEED-WITH-CONDITIONS(조건 = `articles.session_id` NOT NULL 가드 누락, 회귀 테스트 케이스 M 추가로 해소). 사용자 bootRun으로 Supabase(PostgreSQL 17.6)에 V2 적용 확인 — Flyway `Successfully applied 1 migration ... now at version v2`.

## Consequences

### 긍정 (Benefits)

- "모르면 모른다" 도메인 불변식이 코드뿐 아니라 DB 레벨로 강제된다 — 향후 검증 파이프라인(Sprint 2 Service 4종)이 잘못된 row를 쓰면 INSERT가 실패해 즉시 드러난다.
- `VerificationIntegrityMigrationTest` 회귀 가드 12케이스가 V1+V2 적용 스키마에서 불변식을 영구 검증한다. 후속 마이그레이션이 CHECK를 약화시키면 테스트가 깨진다.
- 데이터 0건 시점에 착륙해 마이그레이션 비용이 0이다.
- FK CASCADE 복원으로 분석 세션 삭제 시 하위 그래프가 자동 정리된다.

### 부정 (Drawbacks)

- NOT NULL을 추가한 컬럼을 향후 코드가 채우지 않으면 런타임 INSERT가 실패한다. 이는 hardening의 의도된 효과지만, Sprint 2 Service 구현 시 각 엔티티 빌더가 필수 컬럼을 채우는지 확인해야 한다.
- `AbstractIntegrationTest`는 `create-drop` + `flyway.enabled=false`라 V2 제약을 검증하지 못한다. V2 제약 검증은 `VerificationIntegrityMigrationTest`(raw JDBC, V1+V2 적용)와 `FlywaySchemaParityTest`(Flyway V1+V2 + `ddl-auto=validate`)가 전담한다.

### 중립 (Neutral)

- Hibernate `validate`는 컬럼 존재·타입만 검사하고 nullable/CHECK/DEFAULT/FK 옵션은 검사하지 않는다. 따라서 V2는 `FlywaySchemaParityTest`의 엔티티 parity 검증에 영향을 주지 않는다(엔티티에 `nullable=false`를 추가할 필요 없음).
- DB DEFAULT는 JPA 경로에서 inert다(Hibernate가 값을 명시 INSERT). raw SQL에서만 효과가 있다.

## Alternatives Considered

| 대안 | 기각 이유 |
|------|----------|
| `articles.url`에 NOT NULL 추가 (ERD 4절 원안·PLAN Part A 표기) | `ArticleSource.TEXT_INPUT` 기사는 외부 URL이 없어 `url`이 NULL이다(`Article.fromText()` javadoc 명시 불변식). url NOT NULL은 이 설계와 모순된다. PLAN Part A의 "url NOT NULL → V2" 표기를 본 ADR이 정정한다. `lang`도 `fromText`가 NULL을 허용하므로 동일하게 제외. |
| `score IS NOT NULL` 없는 단순 CHECK (D8 원안 표현 그대로) | SQL 3치 논리상 `tier=1, score=NULL`이 CHECK를 통과하는 구멍이 생긴다. domain-logic상 Tier 1/2는 점수가 있어야 하므로 이는 결함이다. `score IS NOT NULL` 항 추가는 D8 결정 변경이 아니라 정확한 인코딩이다. |
| `verdict` 컬럼 + label 전환을 V2에 함께 탑재 (sprint-2 DISCUSS 제안) | Phase 54 HANDOFF D3("label String 유지, Verdict 미연결")이 변경 금지 결정으로 lock됐다. `.plans/parallel-track-coordination-2026-05-22.md` 5장 Hard Rule이 verdict 컬럼을 sprint-2의 V3로 배정한다. 54 범위 확장은 사용자 승인 + D3 amend가 선행돼야 한다. |
| `analysis_sessions` tier count·`api_usage_logs` 카운터에 NOT NULL+DEFAULT 0 즉시 적용 (ERD 4절 원안) | 해당 엔티티 필드가 nullable이고 Hibernate는 모든 컬럼을 명시 INSERT(explicit NULL)하므로, NOT NULL을 걸면 DB DEFAULT 0이 적용되지 못해 INSERT가 실패한다. NOT NULL+DEFAULT를 안전하게 적용하려면 엔티티 필드 기본값 동반이 필요하다 — 별도 트랙으로 이연. |
| `verification_results.claim_id`에 NOT NULL 추가 (ERD 4절 원안) | HANDOFF가 verification_results NOT NULL 대상을 `tier/label/reason/verified_at` 4컬럼으로 명시 enumerate했다. claim_id NOT NULL은 scope 외이며, 회귀 테스트의 FK 격리 패턴(`ErdContractDriftReproductionTest`)과도 정합하지 않는다. 후속 트랙으로 이연. |
| `factcheck_cache.search_vector`(V3) / RLS 정책(V4) 포함 | 0-2 backlog가 각각 V3(Tier1 한국어 검색 기능 동반)·V4(보안·auth 연동)로 분리 박제. 미사용 인프라 선반영 금지 원칙. |

## References

### Phase 54 산출물

- HANDOFF: `truthscope-web/.plans/54-erd-entity-reconcile/HANDOFF.md` (D1~D8, V2 범위 박제)
- PLAN: `truthscope-web/.plans/54-erd-entity-reconcile/PLAN.md` (Part B + 6절 cross-review)
- ERD 정정 정본: `truthscope-web/docs/md/10_erd.md` 0절 (V1 verbatim + 0-2 backlog + 0-3 델타 + 4절 원안 DDL)
- 병행 트랙 조율: `truthscope-web/.plans/parallel-track-coordination-2026-05-22.md` (ADR 네임스페이스·Flyway 버전·verdict V3 Hard Rule)

### 코드 산출물

- 마이그레이션: `app/src/main/resources/db/migration/V2__hardening.sql`
- 회귀 가드: `app/src/test/java/com/truthscope/web/drift/VerificationIntegrityMigrationTest.java` (12 케이스)
- 동결 기준 V1: `app/src/main/resources/db/migration/V1__init_schema.sql`

### 선행 결정·도메인 근거

- ADR-008(Database Migration Standard) — `docs/architecture/decisions/ADR-008-database-migration-standard.md`
- domain-logic 3-Tier Cascade — `.claude/rules/domain-logic.md` ("모르면 모른다", Tier 3 score=NULL)
- cross-review thread: codex gpt-5.5 `019e39df-9714-7852-897f-40abe85d4d3b`

## Change Log

| 날짜 | 변경 내용 |
|------|----------|
| 2026-05-22 | Status: Accepted — cross-review 2종 통과(codex `019e4f30` PROCEED, Claude reviewer PROCEED-WITH-CONDITIONS) + 회귀 가드 케이스 M(`articles.session_id`) 추가, 131→144 tests green + Supabase v2 적용 확인 |
| 2026-05-22 | Initial draft (Phase 54 V2 hardening 실행 — V2__hardening.sql + 회귀 가드 작성) |
