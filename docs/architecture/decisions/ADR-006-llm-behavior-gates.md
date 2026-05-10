---
number: "006"
title: "LLM Behavior Gates 4-gate PR-level Input Contract"
status: "Accepted"
date: "2026-05-10"
authors: ["gs07103"]
---

# ADR-006 — LLM Behavior Gates 4-gate PR-level Input Contract

## Context

### 배경

TruthScope backend 레포에서 LLM 에이전트가 PR을 생성할 때 downstream 자동화(ralph 루프, discord bot, CodeRabbit 워크플로우)가 안전하게 동작하려면 PR-level **입력 계약(input contract)**이 명확해야 한다는 필요성이 제기됐다.

초기 검토에서 Codex gpt-5.5 Round 1~3 자문을 통해 6개 gate 초안이 검토됐다:
1. PR scope (SAFE_OP 마커)
2. Plan-Review-Deep §1 위임 (F1.a-d)
3. Done 섹션 embed
4. pr-meta-check.yml workflow 자동 강제
5. 리뷰어 routing
6. 릴리즈 노트 자동 생성

Round 3 자문 결과, **6 gate → 4 gate 축소** 컨센서스에 도달했다.
Gate 5(리뷰어 routing)는 plan-review-deep으로, Gate 6(릴리즈 노트 자동 생성)은 changelog-timeline-guide로 위임함으로써 각 artifact의 단일 책임 원칙을 보존했다.

### 선행 근거

- Karpathy LLM 코딩 가이드라인(MIT) 4 원칙 중 행동 영역: Gate 1(Goal Defined) / Gate 2(Assumption Surfaced) / Gate 3(Surgical Diff) / Gate 4(Minimum Code)는 각각 Karpathy §4/§1/§3/§2 원칙을 PR-level 행동 계약으로 변환한 것이다.
- `.claude/rules/plan-review-deep.md` §1의 F1.a-d 4 subfacet(Reproducible Failure / Staged Gate / Immutable Verification / Full-Solution Verification)은 **downstream 실행/검증 계약**으로, 본 ADR이 다루는 upstream 입력 계약과 역할이 분리된다.
- 중복 정의 방지: 테스트 커버리지 정량 기준, verification 형식, E2E 필수 요건은 F1.a-d의 단일 소스 오브 트루스이므로 본 rule에 추가하지 않는다.

## Decision

**4 gate를 PR-level LLM 행동 계약으로 채택한다.**

### 4 Gate 정의

| # | Gate | 통과 기준 |
|---|------|----------|
| F1 | **Goal Defined** | PR 본문 `## Done` 섹션에 Done 체크리스트(요구사항 목록 + 산출물 목록 + 수용 테스트) 존재 |
| F2 | **Assumption Surfaced** | PR 본문 SAFE_OP 마커 블록에 Assumptions / Scope / Out-of-scope 3줄 명시 |
| F3 | **Surgical Diff** | 변경 라인이 모두 사용자 요청에 직접 추적 가능. 명문 예외(보안 패치 hotfix · CI 차단 dead code) 외 인접 코드 개선 금지 |
| F4 | **Minimum Code** | 단일 사용처 추상화 금지. 요청 외 유연성 금지. "200줄을 50줄로?" 통과 기준 |

### Plan-Review-Deep §1 위임 (F1.a-d)

본 rule이 다루지 않는 영역(테스트 커버리지 정량 기준, 재현 가능성 verification 형식, E2E 필수 요건)은 `.claude/rules/plan-review-deep.md` Section 1의 F1.a-d 4 subfacet으로 위임한다. 본 ADR은 그 **entry condition**을 정의할 뿐이다.

### Trivial 예외

≤10 LOC + 새 의존성 0 + 새 파일 0 + 동작 변경 0인 trivial 변경은 F1 Gate를 Micro-Done(1줄 형식)으로 완화한다.

### 자동 강제 (pr-meta-check.yml)

`.github/workflows/pr-meta-check.yml` workflow가 다음을 자동 검사한다:
- SAFE_OP 마커 블록 존재 → **차단**
- `## Done` 섹션 존재 (또는 Micro-Done) → **차단**
- trivial 라벨 vs diff LOC 자동 계산 일치 → **차단**
- Scope 업데이트 코멘트 시 scope-ack 라벨 또는 ACK SCOPE 코멘트 → **차단**

Bot(dependabot 등) PR은 `jobs.check.if: pull_request.user.type != 'Bot'` 조건으로 skip 처리한다.

## Status

`Accepted`

BE#50 MERGED (2026-05-XX). llm-setup-templates 3 repos (spring PR#28 / python PR#33 / typescript PR#23) batch validation 완료 후 TruthScope backend 레포에 동일 계약 박제.

## Consequences

### 긍정 (Benefits)

- PR scope가 SAFE_OP 마커로 명확화되어 코드 리뷰어와 자동화 도구가 의도 파악이 빠르다.
- pr-meta-check.yml workflow로 계약 위반이 CI 단계에서 자동 차단된다.
- downstream 자동화(ralph 루프, discord bot 공지, CodeRabbit 리뷰)가 SAFE_OP 마커 + Done 섹션을 파싱해 안전하게 동작한다.
- 6 gate에서 4 gate로 축소함으로써 단일 책임이 강화됐다(리뷰어 routing = plan-review-deep, 릴리즈 노트 = changelog-timeline-guide).
- Karpathy 원칙과 1:1 매핑되어 외부 근거가 명확하다.

### 부정 (Drawbacks)

- PR 생성 시 SAFE_OP 마커 블록 + Done 섹션이 필수가 되어 소규모 fix에도 보일러플레이트가 늘어난다(trivial Micro-Done으로 완화).
- 팀원이 새 형식에 적응하는 초기 비용이 존재한다.

### 중립 (Neutral)

- F4 pr-meta-check.yml workflow가 GitHub Actions에 종속된다. 다른 CI 시스템(GitLab CI, Bitbucket Pipelines)으로 이전 시 workflow 재구현이 필요하다.
- Gate 3/4 위반은 workflow가 자동 차단하지 않고 CodeRabbit 리뷰 + 로컬 리뷰 에이전트가 지적한다.

## Alternatives Considered

| 대안 | 기각 이유 |
|------|----------|
| **6 gate 유지** | Gate 5(리뷰어 routing)와 Gate 6(릴리즈 노트)가 각각 plan-review-deep + changelog-timeline-guide와 책임이 겹쳐 cross-drift 발생. F1 권위 약화. |
| **0 gate (free-form PR)** | downstream 자동화(ralph 루프, discord bot)가 파싱할 구조적 계약 없이 무력화. |
| **외부 governance 도구 (Probot, Danger JS 등)** | npm/Node 의존성 추가 + 별도 설정 파일 관리 부담. GitHub Actions 네이티브 workflow로 동등한 효과 달성 가능. |
| **PR description 자유 형식 + 코드 리뷰 only** | 반복 리뷰 코멘트 + LLM 행동 불확실성. 자동 강제 없으면 gate 누락률 높음. |

## References

- `.claude/rules/llm-behavior-gates.md` — 본 ADR이 박제하는 rule 전문 (4 gate 상세 정의, 예외 조항, 실패 모드 테이블)
- `.claude/rules/plan-review-deep.md` §1 — F1.a-d 4 subfacet (downstream 실행/검증 계약, F2 위임 대상)
- `.github/PULL_REQUEST_TEMPLATE.md` — SAFE_OP 마커 블록 + Done 섹션 임베드
- `.github/workflows/pr-meta-check.yml` — F4 자동 강제 workflow (trigger: pull_request + issue_comment)
- Codex gpt-5.5 thread (R1~R3 자문, 6→4 gate 축소 컨센서스)
- [Karpathy LLM Coding Guidelines](https://github.com/forrestchang/andrej-karpathy-skills) (MIT) — Gate 1/2/3/4 원칙 출처
- 메모리 `project_llm_behavior_gates_pilot.md` — BE#50 + spring/python/typescript 3 templates 머지 박제 (2026-05-07)

## Change Log

| 날짜 | 변경 내용 |
|------|----------|
| 2026-05-10 | Accepted — BE#50 머지 + llm-setup-templates 3 repos batch validation 완료로 Accepted. backend 레포 ADR 인프라 신설과 함께 박제. |
