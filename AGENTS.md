# AGENTS.md — TruthScope Backend

> 모든 AI 도구 (Codex CLI / GitHub Copilot / Cursor / Claude Code 등) 공통 instruction.
> 사람이 보는 상세 절차서는 `docs/guides/` 참조.
> Claude Code 세부 규칙은 `CLAUDE.md` 보존 (본 파일과 정합 유지).

---

## 1. 절대 금지 (모든 PR에 강제)

| 금지 | 이유 |
|---|---|
| `@Setter` on Entity / `@Data` / `@ToString` on Entity | ArchUnit 룰 — `entityShouldNotExposeSetters` 통과 의무 |
| `EntityType.ORDINAL` (Enum 매핑) | `@Enumerated(EnumType.STRING)` 강제 — 컬럼 추가/순서 변경 시 데이터 손상 방지 |
| `FetchType.EAGER` 관계 매핑 | 모든 관계는 `LAZY` 의무 — N+1 회피 |
| `gemini-2.0-flash-lite` 모델 사용/언급 | 1순위 `gemini-3.1-flash-lite` / 2순위 `gemini-2.5-flash-lite`만 허용 |
| `--no-verify` git push (pre-commit/CI hook 우회) | hook 실패 시 원인 수정 의무 |
| `springdoc-openapi 2.8.6 이하` | Spring Boot 3.5.x 비호환 (HateoasProperties NoSuchMethodError) — **2.8.9+ 필수** |

---

## 2. 핵심 패턴 (PR 작성 시 따라야 할 invariant)

### Entity (JPA)
- `@Getter` + `@NoArgsConstructor(access = PROTECTED)` + `@Builder(access = PRIVATE)` + `@AllArgsConstructor(access = PRIVATE)`
- 변경은 비즈니스 메서드로 (`attachTo()` 같은 도메인 의도 명시)
- `@GeneratedValue(strategy = GenerationType.UUID)` 기본 (Member는 Supabase Auth UUID 예외)
- `extends BaseTimeEntity` — 예외 3종: `ApiUsageLog` (immutable log), `VerificationTrace` (D3 재현성 audit, `created_at`만 필요 단계로 `updated_at` 무의미), `DataSourceSnapshot` (`retrieved_at` 독자 의미론). 예외는 `@CreationTimestamp` 직접 사용 (Phase 19 BE #21 ADR-008 D3 정합)
- 상세: `docs/guides/backend-jpa-entity-template.md`

### DDD/TDD aggregate (Phase 21 학습 reference)
- 정적 팩토리 메서드 (`Article.extract(url, ...)`) 로만 인스턴스 생성
- URL invariant 같은 비즈니스 invariant는 정적 팩토리에서 검증
- 상태 전환은 `attachTo()` 같은 비즈니스 메서드로만 (직접 setter 호출 금지)
- 재호출 시 `IllegalStateException` throw → `ConflictException(409)` 매핑
- 상세: `docs/guides/backend-ddd-tdd-template.md`

### DTO
- 요청: `record` 타입 (Java 17+) + `@Valid` 검증
- 응답: 클래스 + `@Getter` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor`
- 패키지: `dto/request/`, `dto/response/`

### Service
- `@Transactional` (쓰기) / `@Transactional(readOnly = true)` (읽기)
- 생성자 주입만 (`@RequiredArgsConstructor` + `private final`) — `@Autowired` 필드 주입 금지
- Entity 직접 노출 금지 → 반드시 DTO 변환 (Converter는 `converter/` 패키지, static, `@NoArgsConstructor(PRIVATE)`)

### Controller
- URL: `/api/v1/{자원복수형}` — 동사 금지, 복수형 명사, 하이픈 구분, 소문자만
- HTTP Method: GET 조회 / POST 생성 / PATCH 부분수정 / DELETE 삭제 (PUT은 전체 교체만)
- 단순 200: 객체 직접 반환 (ResponseEntity 불필요), 201/204만 ResponseEntity 사용
- Springdoc 어노테이션: `@Tag` + `@Operation` + `@ApiResponse` 의무

### Exception
- `GlobalExceptionHandler`에서 일괄 처리 → `ApiErrorResponse { status, statusCode, message }`
- 도메인 예외: `ConflictException(409)` / `IllegalArgumentException → 400` / `IllegalStateException → 409`
- 상세: `docs/guides/backend-error-handling.md` (Sprint 2 ErrorCode 채택 후 작성)

### 통합 테스트 (Testcontainers)
- `extends AbstractIntegrationTest` (Singleton container 패턴, `@ServiceConnection PostgreSQLContainer`)
- 클래스 레벨 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` autowire
- 5+ test cases per controller (happy / 404 / invalid input / conflict / type mismatch)

---

## 3. 테스트 명령어 (PR 전 필수)

```bash
./gradlew spotlessApply      # 포맷 자동 수정 (Google Java Format)
./gradlew checkstyleMain     # 린트
./gradlew spotbugsMain       # 정적 분석
./gradlew test               # 전체 테스트 (Testcontainers 포함)
./gradlew build              # 최종 빌드
```

상세 절차 (격리 / Docker 의존성 / 일시 실패 처리): `docs/guides/testing-standard.md`

---

## 4. 멤버별 진입 경로 (Sprint 2 Week 1)

| 멤버 | 시작 이슈 | 주 가이드 | 보조 가이드 |
|---|---|---|---|
| 한지민 | BE #22 (DataSourceAdapter 인터페이스) | `docs/guides/backend-datasource-adapter.md` | `docs/guides/testing-standard.md` |
| 이정훈 | BE #24 (UrlInputAdapter) | `docs/guides/backend-url-input-adapter.md` | `docs/guides/backend-ddd-tdd-template.md` |
| 권석 (PM) | BE #20 SSRF / BE #23 Gradle 분리 | 본 AGENTS.md + `docs/guides/*` 일관성 검수 | — |

---

## 5. PR 체크리스트 (모든 PR 본문에 복사)

- [ ] `./gradlew spotlessApply / checkstyleMain / spotbugsMain` PASS
- [ ] `./gradlew test` PASS (회귀 0)
- [ ] `./gradlew build` PASS
- [ ] ArchUnit 룰 PASS (entity setter 금지)
- [ ] Springdoc 어노테이션 추가 (controller 신규 시)
- [ ] PR 본문에 spec 이슈 번호 + 학습 가치 1줄 명시
- [ ] CodeRabbit 리뷰 통과 (Critical 0)

---

## 6. 커밋 메시지 (Gitmoji)

형식: `{이모지}{type}({scope}): {description}`

```
✨feat(controller): ArticleController + GET/POST endpoints (Phase 21 W3)
🐛fix(test): AbstractIntegrationTest singleton container (CI hotfix)
♻️refactor(service): ArticleService dirty checking 활용
📝docs(guide): backend-ddd-tdd-template.md 신규
🔧chore(deps): springdoc 2.8.6 → 2.8.9
✅test(controller): ArticleControllerTest 7 cases
🔒fix(article): URL invariant validateUrl 예외 메시지에서 원본 URL 제거
```

Co-authored-by 의무 (PM 본인): `Co-authored-by: KWONSEOK02 <gwonseok02@gmail.com>` (2026-05-23 통일, 이전 `gs07103` deprecated)

---

## 7. 5/4 Sprint 2 Week 1 진입 전 PM 의무

- BE #20 SSRF Week 1 blocker 격상 + 최소 정책 PR (멤버 작업 진입 전)
- BE #22 DataSourceAdapter scaffold reference 작성 → `docs/guides/backend-datasource-adapter.md` 박제
- D7 90분 onboarding session 일정 공지 (Phase 21 DDD/TDD 패턴 walk-through)
