<!--
SAFE_OP 마커 블록과 ## Done 섹션은 자동 검증 대상입니다 (`.github/workflows/pr-meta-check.yml`).
삭제·마커 변경 시 PR 차단. 자세한 4 Behavior Gates: `.claude/rules/llm-behavior-gates.md`.
-->

<!-- SAFE_OP_START -->
Assumptions: [기본값에서 벗어난 가정만, 없으면 "없음"]
Scope: [변경할 파일/디렉토리]
Out-of-scope: [의도적 미변경 영역]
<!-- SAFE_OP_END -->

## Done

표준 Done (다음 3가지 모두 명시):

- [ ] **요구사항 목록** (입력 / 출력 / 에러 / 경계 중 1+ 포함):
- [ ] **산출물 목록** (파일 / 엔드포인트 / CLI 명령):
- [ ] **수용 테스트** (산출물 타입에 맞는 레벨, 자동화 가능):

또는 trivial 작업의 경우 Micro-Done (1줄):

```
요구사항: [한 줄] / 변경: [파일 경로] / 검증: 기존 테스트 통과
```

## Behavior Gates 자체 점검

- [ ] Gate 1 — Goal Defined (Done 정의 위 명시)
- [ ] Gate 2 — Assumption Surfaced (SAFE_OP 마커 블록 위 작성)
- [ ] Gate 3 — Surgical Diff (변경 라인 모두 요청 추적, 명문 예외 외 인접 코드 개선 없음)
- [ ] Gate 4 — Minimum Code (단일 사용처 추상화·요청 외 유연성 없음)

> 실행/검증 (테스트·CI green·재현·E2E)은 F1.a-d (`.claude/rules/plan-review-deep.md` §1)로 위임. 본 점검은 PR-level 입력 계약만.

---

## 관련 Issue
closes #이슈번호

## 변경 사항

## 체크리스트
- [ ] 빌드 성공
- [ ] 관련 테스트 통과 (있으면)
- [ ] Issue의 수용 기준 모두 충족
- [ ] 불필요한 console.log / System.out.println 제거

## 스크린샷 (UI 변경 시)
