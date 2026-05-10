---
number: "008"
title: "Database Migration Standard"
status: "Accepted"
date: "2026-05-11"
authors: ["KWONSEOK02"]
---

# ADR-008 — Database Migration Standard

## Context

2026-05-11 운영 적용 시도 중 발견: 운영 Supabase DB는 `articles` 등 모든 테이블이 부재 (2026-05-11 SELECT 검증). BE는 `ddl-auto: none` + Flyway/Liquibase 의존성 0건 + `supabase/migrations/` 디렉토리 부재 → BE 첫 배포 시 schema bootstrap 수단 없음. Manual SQL capsule (`docs/db/manual/2026-05-12-*.sql`)은 "기존 테이블에 컬럼 추가" 용도로 설계 → first-bootstrap 대응 불가.

Issue #52 (Sprint 3 일정)를 2026-05-11 사고로 조기 진입. Codex 5.5 2026-05-10 thread `019e11bd` Q4가 Supabase migration native workflow 1순위 검토 권고.

관련 ADR:
- ADR-006: LLM behavior gates (PR-level input contract)
- ADR-007: be24-input-adapters-cleanup phase의 in-progress draft (`truthscope-web/.plans/be24-input-adapters-cleanup/ADR-007-draft.md`). 본 디렉토리로 promotion 전 — README §3 메모 참조. Issue #52는 numbering gap을 인정하고 ADR-008로 진행 (PM 2026-05-11 결정).

## Decision

We will adopt **Option D (Hybrid B+A)**:
- **Primary**: Flyway (Spring Boot 3.x native auto-config) for schema migration
- **Secondary**: Supabase CLI opt-in (Branching/RLS 등 BaaS 기능)은 Sprint 3+1 별도 ADR로 결정

핵심 이유:
1. Spring Boot 부팅 시 JPA 초기화 전 Flyway 자동 실행 → schema/code 동기화 framework-level 보장 (2026-05-11 사고 재발 차단)
2. PostgreSQL 단일 DB 환경에서 Liquibase의 DB-agnostic DSL pay rent 부족
3. plain SQL 학습 곡선이 8주 academic 팀에 적합 (Fowler+Fontaine 거장 컨센서스, Codex thread `019e12af`)
4. 기존 manual SQL의 "5종 표준" 자산 (lock_timeout + RAISE guard + ANALYZE + rollback policy)을 V*.sql로 직접 carry-over 가능

본 ADR은 도구 표준만 결정한다. 실제 코드 변경(`flyway-core` 의존성 추가, V0/V1 SQL 작성, AbstractIntegrationTest:28 변경, `_legacy/` archive 등)은 Sprint 3 Week 1 (2026-05-17~) 별도 PR `feat/52-flyway-migration-standard`에서 PLAN.md §3 T1~T9 순차 commit으로 진행한다.

## Status

`Accepted` (LOCKED 2026-05-11 at Step 4 plan-review-deep Critical-0 convergence + PROCEED-WITH-CONDITIONS)

## Consequences

### 긍정 (Benefits)

- Spring Boot 부팅 = schema 동기화 자동 보장 (2026-05-11 사고 재발 차단)
- Plain SQL 명명 규칙 (`V<version>__<desc>.sql`) — 학습 곡선 최소
- BE PR review와 schema 변경 review가 같은 channel (gh PR review)
- Testcontainers 통합 자동 (`@ServiceConnection` + Singleton container 패턴 기존 그대로)

### 부정 (Drawbacks)

- Supabase Branching/RLS native 통합은 Sprint 3+1 별도 ADR로 미룸 (compromise C5-1)
- 무료 rollback 미지원 → forward V*.sql reverse 패턴 학습 필요 (Liquibase 대비)
- Flyway 10.x checksum 검증으로 V*.sql 사후 편집 시 dev 워크플로우 정착 필요 (devtools hot-reload 호환)

### 중립 (Neutral)

- ADR-006 영향 없음 (별개 영역: LLM behavior gates vs DB migration)
- 기존 BE #24 manual SQL은 `_legacy/` archive로 보존 (PR/cross-review 이력 carry-over, PR #55 `332fea7`)

## Alternatives Considered

| 대안 | 기각 이유 |
|------|----------|
| **A. Supabase CLI primary** | Spring Boot 부팅 통합 부재 (외부 단계) — 2026-05-11 사고 재발 위험. CLI 학습 부담 추가. (Abandonment Cost: 5 — compromise C5-1로 보존) |
| **C. Liquibase** | DB-agnostic DSL pay rent 부족 (PostgreSQL 단일). XML/YAML 학습 부담이 8주 일정에 부담. 무료 rollback은 "forward V*.sql reverse" 패턴으로 충분히 대체 가능. (Abandonment Cost: 3) |
| **D. Manual SQL capsule (현 상태)** | 2026-05-11 사고가 first-bootstrap 한계 증명. Spring Boot 부팅 동기화 부재. (Abandonment Cost: 1 — one-off ops로 부수 보존만) |

## Abandonment Cost

| Rejected option | Score | Status | Reason | Compromise |
|---|---:|---|---|---|
| Option A (Supabase CLI primary) | 5 | Locked | BaaS-native ecosystem 일찍 잠금 | C5-1: Sprint 3+1 별도 ADR opt-in |
| Option B (Flyway 거부 = D 안 채택) | 3 | Locked | Single dep 추가, 후행 도입 가능 | 불필요 |
| Option C (Liquibase) | 3 | Locked | DB-agnostic DSL pay rent 부족 | 불필요 |
| Option D (Manual capsule) | 1 | Locked | One-off ops 부수 보존 | 불필요 |

Threshold policy: 5+ requires compromise design. Option A score 5 → C5-1 compromise.

Rubric: `Guide/Abandonment-Cost-Scoring-Rubric` (옵시디언 vault 노트 — GitHub 외부 참조. Phase 13c + DDD/TDD 통합 + Phase 52 본 ADR 3번째 적용).

## References

### 의사결정 산출물

- Issue #52 본체: <https://github.com/truthscope-smu/truthscope-web-backend/issues/52>
- DISCUSS.md: `truthscope-web/.plans/52-db-migration-standard/DISCUSS.md`
- CROSS-REVIEW.md: `truthscope-web/.plans/52-db-migration-standard/CROSS-REVIEW.md`
- PLAN.md: `truthscope-web/.plans/52-db-migration-standard/PLAN.md`
- HANDOFF.md: `truthscope-web/.plans/52-db-migration-standard/HANDOFF.md`

### Cross-review threads

- Codex 5.5 거장 5명 자문 (Sadalage / Fowler / Fontaine / Voxland / Copplestone): thread `019e12af-681d-72f0-994f-90ca0ef4de48`
- Codex 5.5 plan-review-deep Round 1: thread `019e12c6-07fb-76a2-b31a-11366d56920a`
- Opus 서브 에이전트 Step 2 코드베이스 검증
- Opus 서브 에이전트 Step 4 R1 Completeness + Runtime Contract Lens

### 1차 출처 (firecrawl_scrape verified 2026-05-11)

- [Supabase Database Migrations](https://supabase.com/docs/guides/deployment/database-migrations)
- [Flyway Migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations)
- [Fowler — Evolutionary DB Design](https://www.martinfowler.com/articles/evodb.html)
- Sadalage & Ambler, *Refactoring Databases* (2006), Ch.1 + Ch.4

## Change Log

| 날짜 | 변경 내용 |
|------|----------|
| 2026-05-11 | Initial draft (Step 5 ADR 작성, PLAN.md T8 carry-over) |
| 2026-05-11 | Status: Accepted (Step 4 plan-review-deep Round 1 Critical-0 convergence + PROCEED-WITH-CONDITIONS, 자가 검증 채택으로 Round 2 생략) |
| 2026-05-11 | CodeRabbit Round 1 Actionable 적용: ISO 날짜 통일 (`5/11`/`5/10` → `2026-05-11`/`2026-05-10`), Obsidian wiki link → backtick text |
