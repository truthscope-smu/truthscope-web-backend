# LLM Behavior Gates — PR-level Input Contract

> LLM 에이전트가 PR을 만들 때 통과해야 하는 **4 행동 게이트**.
> Karpathy LLM 코딩 가이드라인(MIT) 4 원칙 중 행동 영역을 박제.
> 실행/검증 계약은 [`plan-review-deep.md`](./plan-review-deep.md) Section 1의 **F1.a-d 4 subfacet**이 단일 소스 오브 트루스 — 본 rule은 **PR-level 입력 계약**(LLM 행동)만 다룸. 중복 정의 금지.

## Role Separation (One-Liner)

- **이 rule (Behavior Gates)** = Upstream LLM 행동 계약 — 무엇을 명시하고 무엇을 하지 말 것인가
- **F1.a-d (plan-review-deep §1)** = Downstream 실행/검증 계약 — repro / staged / immutable / full-solution

테스트 우선·CI green·재현 가능성·E2E coverage 등은 **F1로 위임**. 본 rule은 그 entry condition.

### 본 rule이 다루지 않는 영역 (F1으로 위임 — 금지 예시)

본 rule에 다음 항목을 추가 작성·박제 금지. F1.a-d (`plan-review-deep.md` §1)이 단일 소스 오브 트루스:

- ❌ "테스트 커버리지 X% 이상" 같은 정량 기준
- ❌ "재현 가능한 실패는 SHA 포함 명시" 같은 verification 형식
- ❌ "E2E 테스트 1개 필수" 같은 full-solution 요구

위 3종은 F1.a (Reproducible Failure), F1.c (Immutable Verification), F1.d (Full-Solution Verification) 영역. 본 rule에 적으면 cross-drift 발생 + F1 권위 약화. PR review 시 본 rule에서 발견되면 제거하고 F1로 위임할 것.

## 4 Behavior Gates

| # | Gate | 통과 기준 (자동 검증 가능) |
|---|------|------------------------|
| 1 | **Goal Defined** | Done 체크리스트 (또는 Micro-Done)가 PR 본문 `## Done` 섹션에 존재. 산출물 + 수용 테스트 명시. |
| 2 | **Assumption Surfaced** | SAFE_OP 마커 블록에 Assumptions / Scope / Out-of-scope 3줄 명시. 기본값에서 벗어난 가정은 사용자 확인. |
| 3 | **Surgical Diff** | 변경 라인이 모두 사용자 요청에 직접 추적. 명문 예외(보안 패치 hotfix · CI 차단 dead code) 외 인접 코드 개선 금지. |
| 4 | **Minimum Code** | 단일 사용처 추상화·요청 외 유연성·발생 불가 시나리오 error handling 금지. "200줄 짠 게 50줄로 가능?" 통과. |

## Gate 1 — Done 정의

### 표준 Done

다음 3가지가 PR 본문 `## Done` 섹션에 명시:

- [ ] **요구사항 목록** (검증 가능한 AC): 입력 / 출력 / 에러 케이스 / 경계 조건 중 1+ 포함
- [ ] **산출물 목록**: 생성·수정될 파일 / 엔드포인트 / CLI 명령
- [ ] **수용 테스트**: 산출물 타입에 맞는 레벨 (unit / contract / integration / smoke / e2e) 1+

### Trivial Micro-Done

trivial 트리거 충족 시 1줄 형식, 사용자 확인 없이 진행:

```
요구사항: [한 줄] / 변경: [파일 경로] / 검증: 기존 테스트 통과
```

## Gate 2 — SAFE_OP 마커 블록

PR 본문에 다음 블록 의무. 정규식 대신 마커 사용 — 빈줄/리스트/추가 텍스트에 견고.

```
<!-- SAFE_OP_START -->
Assumptions: [기본값에서 벗어난 가정만, 없으면 "없음"]
Scope: [변경할 파일/디렉토리]
Out-of-scope: [의도적 미변경 영역]
<!-- SAFE_OP_END -->
```

`pr-meta-check.yml` workflow가 마커 사이 필드만 파싱.

### Scope 변경 절차

작업 중 Scope 외 파일 수정이 필요해지면:

1. 작업 중단
2. PR 본문 SAFE_OP 블록의 Scope 라인 갱신 + 추가 코멘트:
   `Scope 업데이트: [추가 파일] — 이유: [한 줄]`
3. 사용자가 PR에 라벨 **`scope-ack`** 또는 코멘트 **`ACK SCOPE`** 첨부 (OR, 둘 다 OK)
4. **외부 포크 PR**은 라벨 권한 없음 → 코멘트 `ACK SCOPE`만 허용
5. workflow가 라벨/코멘트 존재 확인 후 재개 허용

## Gate 3 — 허용 인접 변경 한도

### 직접 추적의 정의

해당 변경이 없으면 테스트 / 빌드 / 요구사항 / 보안 검증 / CI 중 하나가 실패.

### 허용

- (a) 요청한 파일
- (b) 직접 컴파일/린트 실패를 막는 최소 라인
- (c) 같은 파일 내 의존되는 import 추가
- (d) **명문 예외 1**: CI 워크플로우를 차단하는 dead code 제거. PR 본문에 차단 로그 인용 필수.
- (e) **명문 예외 2 (hotfix)**: 보안 업그레이드가 빌드 또는 CI 차단 원인일 때 (예: deps 취약점 install 실패). 동 PR 포함 허용. 범위는 의존성 1개 + 그에 따른 최소 코드 수정. PR 본문에 차단 로그(스캐너 / build) 인용 필수.

### 금지

- (a) 무관한 포맷 정리
- (b) 무관한 리네이밍
- (c) 다른 파일의 "있는 김에" 개선
- (d) 요청 없는 dead code 삭제 (위 명문 예외 외)
- (e) 코드 스타일 통일 (lint/formatter 외 임의 적용)
- (f) **보안 패치 의존성 업그레이드는 본 PR 포함 금지** — 반드시 별도 PR (단 위 명문 예외 2 hotfix는 예외)

## Gate 4 — Minimum Code

- 요청 외 기능 추가 금지
- 단일 사용처에 추상화 금지 (DRY는 중복 3회 이상부터 적용 — 같은 파일 내 + 함수 1개 추출만 허용)
- 요청되지 않은 "유연성" / "설정 가능성" 금지
- 발생 불가 시나리오 error handling 금지
- 200줄 → 50줄 가능하면 다시 쓰기

## 예외 조항 (형식적 트리거)

| 컨텍스트 | 형식적 트리거 | 완화 게이트 | Done 형식 | 머지 |
|---------|------------|-----------|---------|------|
| **trivial** | ≤10 LOC + 새 의존성 0 + 새 파일 0 + 동작 변경 0 | Gate 1 (Micro-Done) | Micro-Done | Yes |
| **--draft** | 사용자 메시지에 `--draft` 명시 (브랜치명·ENV 불인정) | Gate 4 | 표준 Done | No |
| **spike** | `spike/` 디렉토리 하위 | Gate 4 | 표준 Done | No (기본 머지 금지) |

### "동작 변경 0" 정의 (trivial 판정용)

다음 중 **하나라도** 변경되면 ≠ 0:

- 공개 인터페이스 (function signature / export / API endpoint / CLI flag)
- UX (사용자 출력 / 에러 메시지 / 응답 형식)
- 기존 관측성 (**기존** 로그·레벨 / 메트릭 이름 / trace span). 새 로그 추가는 변경 0.
- 성능 특성 (asymptotic complexity / I/O 횟수)

**예시**:
- ✅ 변경 0: 주석 추가, README 오타, 미사용 변수 제거, 새 로그 라인 추가, 외부 비노출 변수 리네이밍
- ❌ 변경 ≠ 0: 기존 로그 메시지 변경, 로그 레벨 변경, 메트릭 이름 변경, default 인자 변경, 에러 메시지 문구 변경

## Failure Modes

| 안티패턴 | 차단 게이트 |
|---------|-----------|
| "make it work" 같은 모호 목표 | Gate 1 |
| 조용히 가정하고 진행 | Gate 2 |
| 인접 코드 "개선" | Gate 3 |
| 가짜 유연성·미래 추상화 | Gate 4 |
| 의존성 과다 추가 | Gate 4 (+ F1.b downstream) |
| 비결정적 작업 (latest 태그) | Gate 2 (+ F1.c downstream) |

테스트 약화·skip / 로컬 통과만 / 재현 불가 등 **실행 결과 측 위반은 F1.a-d로 위임**. 중복 정의 금지.

## Workflow 자동 강제 (`pr-meta-check.yml`)

| 검사 | 정책 |
|-----|----|
| SAFE_OP 마커 블록 존재 | **차단** |
| `## Done` 섹션 존재 (또는 Micro-Done) | **차단** |
| trivial 라벨 vs diff 자동 계산 일치 | **차단** (라벨=trivial인데 LOC>10 → 차단) |
| Scope 업데이트 코멘트 시 scope-ack 라벨 또는 ACK SCOPE 코멘트 | **차단** |
| 수용 테스트 레벨 매트릭스 매칭 | 경고 (review 책임, 초기 2주 한정) |

trigger: `pull_request` (open/edit/sync/labeled/unlabeled/reopened) + `issue_comment` (created/edited).
권한: `contents: read` + `pull-requests: write`. **`pull_request_target` 사용 안 함** (보안 회피).

## License & Attribution

- Karpathy 4 원칙: [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills) (MIT). 본 rule의 Gate 1/2/3/4는 그 §4/§1/§3/§2를 PR-level 행동 계약으로 박제.
- F1 4 subfacet 위임: [`.claude/rules/plan-review-deep.md`](./plan-review-deep.md) Section 1.

## See Also

- [`plan-review-deep.md`](./plan-review-deep.md) §1 — F1.a-d (downstream 실행/검증)
- `docs/architecture/decisions/ADR-006-llm-behavior-gates.md` — 본 rule 채택 근거 (6→4 축소, F1 위임 결정)
- `.github/pull_request_template.md` — SAFE_OP 마커 + Done 섹션 임베드
- `.github/workflows/pr-meta-check.yml` — 자동 강제 workflow
- `.claude/skills/tdd/SKILL.md` — Pocock TDD (Gate 1·F1 entry condition)
