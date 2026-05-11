---
tags: [adr, architecture, governance]
status: active
created: 2026-05-10
---

# Architecture Decision Records

## §1 Nygard ADR 패턴 소개

Architecture Decision Record(ADR)는 소프트웨어 아키텍처 결정과 그 맥락·결과를 짧은 텍스트 문서로 박제하는 패턴이다.
Michael Nygard가 [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)(Cognitect blog, 2011)에서 제안했으며,
MIT Sloan Management Review의 기술 문서화 연구에서도 architecture decision 기록의 중요성이 강조된다.

핵심 원칙:
- 결정 당시의 **Context**(왜 이 선택지가 고려됐는가)를 보존한다.
- 결정이 **Superseded**되더라도 원본 ADR은 삭제하지 않고 상태만 변경한다.
- 짧고(1~2 페이지), 자가 완결형으로 작성한다.

## §2 본 디렉토리 역할

`truthscope-web-backend/docs/architecture/decisions/`는 **backend 레포 코드베이스 레벨의 architecture decision**을 박제한다.

대상:
- backend 서비스 자체 설계 결정 (레이어 구조, 도메인 모델, CI/governance 계약 등)
- Nygard 표준 TEMPLATE.md를 사용하는 ADR 시퀀스

대상이 아닌 것:
- 전역 기술 스택 선정, 팀 협업 방식 등 monorepo 레벨 결정 → `truthscope-web/context/decisions/` 참조

## §3 시퀀스 시작 이유 — ADR-006부터

본 디렉토리는 **ADR-006**으로 시작한다. ADR-001~005는 `truthscope-web/context/decisions/`의 전역 시퀀스에 이미 존재한다.

ADR-006에서 시작하는 이유: `.claude/rules/llm-behavior-gates.md` line 162에 기존 dangling link
(`docs/architecture/decisions/ADR-006-llm-behavior-gates.md`)가 있었으며,
link 경로를 변경하지 않고 해당 파일을 신설함으로써 자동 정합을 달성한다.

향후 신규 backend 레포 자체 결정은 **ADR-007부터** 순차 번호를 사용한다.

> **메모 (2026-05-11)**: ADR-007은 `truthscope-web/.plans/be24-input-adapters-cleanup/` phase의 in-progress draft (`ADR-007-draft.md`)다. 본 디렉토리에 promotion되기 전까지 numbering gap이 발생한다. Issue #52 (database migration standard)가 본 디렉토리에 다음 ADR로 들어오면서 **ADR-008**을 사용한다. ADR-007 draft가 promotion되면 sequential rule 정합 회복.

## §4 두 위치 병존

| 위치 | 역할 | 시퀀스 |
|------|------|--------|
| `truthscope-web/context/decisions/` | 전역 결정 archive (monorepo 레벨 — 기술 스택, 팀 협업, 조직 결정 등) | ADR-001~010+ |
| `truthscope-web-backend/docs/architecture/decisions/` | backend 레포 자체 architecture decision (Nygard 패턴, GitHub repo 컨텍스트) | ADR-006+ |

두 위치는 **별개 시퀀스**이며 중복이 아니다:
- 전역 ADR은 여러 레포에 걸친 결정을 다룬다.
- backend ADR은 이 레포의 코드베이스 내부 결정(governance, 레이어 계약, CI 자동화 등)을 다룬다.

## §5 작성 규칙

- 신규 ADR은 `TEMPLATE.md`를 복사해서 시작한다.
- 파일명: `ADR-{NNN}-{kebab-case-title}.md` (NNN = 3자리 zero-padding)
- status 값: `Proposed` → `Accepted` → `Superseded by ADR-XXX` 또는 `Deprecated`
- Accepted 후 내용 변경 필요 시 → 새 ADR을 작성하고 기존 ADR status를 `Superseded by ADR-NNN`으로 변경
- 변경 이력은 각 ADR의 `## Change Log` 섹션에 추가 (역순 — 최신 항목을 위에)

## §6 참조

- Nygard 원문: [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) (Cognitect, 2011)
- ADR GitHub: [joelparkerhenderson/architecture-decision-record](https://github.com/joelparkerhenderson/architecture-decision-record)
- 전역 결정 archive: `truthscope-web/context/decisions/`
