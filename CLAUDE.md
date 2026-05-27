# TruthScope Backend — 코딩 규칙

> 상세 가이드: `CONVENTIONS.md` 참조
> 커밋 메시지: Gitmoji 사용 (팀 규칙)

---

## 기술 스택
- Spring Boot 3.x (Java 17+)
- JPA / Hibernate + Supabase PostgreSQL
- Lombok

## 엔드포인트

- 모든 API: `/api/v1/{자원복수형}` — `/api`만 쓰지 않는다
- URI에 동사 금지, 복수형 명사, 하이픈(-) 구분, 소문자만
- HTTP Method: GET(조회), POST(생성), PATCH(부분수정), DELETE(삭제)
- PUT은 전체 교체 시에만 — 대부분 PATCH 사용

## 레이어 규칙

- Controller → Service → Repository → Entity (역방향 금지)
- Controller: URL 매핑 + 요청/응답만. 비즈니스 로직 금지
- Service: `@Transactional` (쓰기), `@Transactional(readOnly = true)` (읽기)
- Entity를 Controller/응답에 직접 노출 금지 → 반드시 DTO로 변환

## 클래스/파일 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| Controller | `{도메인}Controller` | `NewsController` |
| Service | `{기능}Service` | `ContentExtractService` |
| Repository | `{Entity}Repository` | `ArticleRepository` |
| Entity | 테이블명 단수형 PascalCase | `Member`, `AnalysisSession` |
| DTO 요청 | `{도메인}Request` | `AnalysisRequest` |
| DTO 응답 | `{도메인}Response` | `ArticleResponse` |
| Converter | `{도메인}Converter` | `ArticleConverter` |

## Entity

- `@Getter` + `@NoArgsConstructor(access = PROTECTED)` + `@Builder` + `@AllArgsConstructor`
- `@Data`, `@ToString` 절대 금지
- `@Enumerated(EnumType.STRING)` 필수 — ORDINAL 금지
- `@Setter` 사용하지 않음 — 변경은 비즈니스 메서드로
- 모든 관계: `fetch = FetchType.LAZY` 필수
- `extends BaseTimeEntity` (ApiUsageLog만 예외)
- PK: `@GeneratedValue(strategy = GenerationType.UUID)` (Member만 예외 — Supabase Auth UUID)

## DTO

- 요청: `record` 타입 (Java 17+) + `@Valid` 검증
- 응답: 클래스 + `@Getter` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor`
- 패키지: `dto/request/`, `dto/response/`

## Converter

- `converter/` 패키지에 `{도메인}Converter` 클래스
- `@NoArgsConstructor(access = PRIVATE)`, static 메서드만
- Service에서 호출 (Controller 아님)

## 응답 형식

- 단순 200: 객체 직접 반환 (ResponseEntity 불필요)
- 201/204 등 상태코드 제어 필요 시만 ResponseEntity 사용
- 에러: GlobalExceptionHandler가 자동 처리 → `{ status, statusCode, message }`
- 페이지네이션: VIA_DTO 모드 사용 — 커스텀 PageResponse 만들지 않는다

## DI

- 생성자 주입만 허용 (`@RequiredArgsConstructor` + `private final`)
- `@Autowired` 필드 주입 금지

## 환경 설정

- `.env` 파일 사용 금지 — `application.yml` + `application-local.yml`
- `application-local.yml`은 Git 금지

## 포맷

- Google Java Format (Spotless): `./gradlew spotlessApply`
- 빌드 전 반드시 포맷 적용

## spike (throwaway 연구 코드) — 2026-05-04 워크스페이스 레벨로 분리

이전에는 `app/src/spike/` sourceSet이었으나 BE 빌드 파이프라인과 격리가 어려워 (CodeRabbit 정책 위반 nitpick · spotbugsTest false positive 등) 워크스페이스 레벨로 외부화함.

| 항목 | 값 |
|---|---|
| 위치 | `truthscope-web/spike/` (BE/FE repo 외부, 워크스페이스 루트) |
| 빌드 | 자체 `settings.gradle` + `build.gradle` (plain Java 17 + JUnit 5 + AssertJ + jsoup + Jackson) |
| Spring 의존 | **0** — 본 BE의 어떤 빌드 task에도 포함되지 않음 |
| git 추적 | 없음 (워크스페이스 자체가 git이 아님) |
| 사용법 / 정책 / 신규 spike 추가 절차 | `truthscope-web/spike/README.md` 참조 |
| 결과물 박제 | 옵시디언/Notion 또는 `.plans/{N}-*/pm-spike/` (이전 위치 그대로) |

> BE repo 안에서는 spike 관련 build.gradle 항목 / `.gitignore` 룰 / `app/src/spike/` 디렉토리가 모두 제거됨. 본 섹션은 위치 안내만 남긴다.

---

## Gemini 모델 — 절대 변경 금지

- 1순위: `gemini-3.1-flash-lite` (2026-05-27 GA 전환으로 `-preview` 제거)
- 2순위 폴백: `gemini-2.5-flash-lite`
- `gemini-2.0-flash-lite` 언급/사용 금지
