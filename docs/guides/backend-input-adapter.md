# Backend Input Adapter 가이드

> **목적**: 사용자 입력(URL / raw text)을 분석 가능한 `Article` 도메인 객체로 만드는 어댑터 작성 시 reference. BE #24 정리 PR에서 확립한 패턴을 후속 어댑터(BE #25 ClaimExtractor 외) 작성 시 그대로 적용.
> **작성일**: 2026-05-10
> **선행 reference**: `docs/guides/backend-ddd-tdd-template.md` (Article aggregate / DDD seed)

---

## 1. 어댑터의 책임 (한 줄)

외부 입력(URL 문자열 / raw text 문자열)을 받아 분석 파이프라인이 통일된 형식으로 다룰 수 있는 `Article` aggregate 한 장으로 변환한다.

어댑터 = Hexagonal Architecture의 **input port 구현체**. 외부 형식의 다양성을 도메인 모델로 흡수하는 ACL(Anti-Corruption Layer) 역할.

---

## 2. ArticleSource — 출처 박제 (필수)

`Article` aggregate에는 항상 **어느 입구로 들어왔는지**가 박제되어야 한다. 본 PR에서 도입한 패턴:

```java
public enum ArticleSource {
  URL_INPUT,    // 외부 뉴스 URL fetch 결과
  TEXT_INPUT    // 사용자 직접 입력 본문
}
```

각 출처에 따라 invariant가 다르므로 같은 aggregate라도 두 개의 정적 팩토리로 분리:

| 팩토리 | source | url 정책 | domain 정책 |
|--------|--------|---------|-----------|
| `Article.extract(url, title, body, lang, domain)` | URL_INPUT | 필수, http(s) 시작 | 외부 호스트명 |
| `Article.fromText(title, body, lang)` | TEXT_INPUT | null 허용 | "user-input" 고정 |

새 출처 추가 (예: 파일 업로드, RSS 구독) 시:
1. `ArticleSource`에 새 값 추가
2. 대응 정적 팩토리 추가 (`Article.fromXxx(...)`)
3. invariant 검증 메서드 추가 (`validateXxx(...)`)
4. 어댑터 작성 시 해당 팩토리 호출

---

## 3. URL 어댑터 패턴 — guard-before-fetch (보안 핵심)

```java
@Component
@RequiredArgsConstructor
public class UrlInputAdapter {

  private final SsrfGuard ssrfGuard;
  private final ContentExtractService contentExtractService;

  public Article extractFromUrl(String url) {
    // 1. SsrfGuard 직접 호출 — fetch 시도 전에 차단
    ssrfGuard.validateAndResolve(url);

    // 2. 가드 통과 후에만 외부 fetch
    ExtractedArticle extracted = contentExtractService.extract(url);

    // 3. 결과를 Article aggregate로 변환 (URL_INPUT 출처)
    return Article.extract(
        url, extracted.getTitle(), extracted.getBody(),
        extracted.getLang(), extracted.getDomain());
  }
}
```

### 왜 어댑터가 SsrfGuard를 직접 호출하는가

`ContentExtractService` 내부에서도 SsrfGuard를 호출하지만, **테스트에서 ContentExtractService를 mock하면 가드 경로가 끊긴다**. 어댑터가 직접 호출함으로써:

- 통합 테스트에서 `@MockBean ContentExtractService` + `verifyNoInteractions` 패턴으로 "차단 시 fetch 안 일어남"을 박제 가능
- defense-in-depth (가드를 두 번 호출해도 SsrfGuard는 idempotent — 성능 영향 미미)
- BDD 시나리오의 "외부 요청은 보내지지 않는다" 요구사항을 코드로 직접 검증

### 던지는 예외 (3종 — 호출자가 다른 메시지로 사용자에게 안내)

| 예외 | 시점 | 사용자 메시지 예시 |
|------|------|------------------|
| `BadRequestException` | URL null/blank/형식 오류 | "URL 형식이 잘못되었습니다" |
| `SsrfBlockedException` | 사설 IP/내부망 | "내부 네트워크 주소는 분석할 수 없습니다" |
| `ExtractionFailedException` | 외부 fetch 실패 (timeout / 5xx) | "기사를 가져올 수 없습니다. 잠시 후 다시 시도해주세요" |

---

## 4. Text 어댑터 패턴

```java
@Component
public class TextInputAdapter {

  public Article extractFromText(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      throw new IllegalArgumentException("text는 null이거나 비어 있을 수 없습니다");
    }

    String trimmed = rawText.trim();
    int firstNewline = trimmed.indexOf('\n');
    String title;
    String body;
    if (firstNewline == -1) {
      // 단일 줄 입력: 제목만, 본문 빈 문자열 (정책 결정)
      title = trimmed;
      body = "";
    } else {
      title = trimmed.substring(0, firstNewline).trim();
      body = trimmed.substring(firstNewline + 1).trim();
    }

    // TEXT_INPUT 출처로 박제 (lang은 MVP에서 "ko" 고정)
    return Article.fromText(title, body, "ko");
  }
}
```

### 정책 결정 (PR #24-cleanup에서 확정)

| 항목 | 정책 | 사유 |
|------|------|------|
| 단일 줄 입력 | valid (body="" 채움) | 짧은 주장도 검증 대상 (Tier 2 검증 처리 가능) |
| lang 코드 | "ko" 고정 (MVP) | 자동 감지 라이브러리는 후속 issue |
| 매우 긴 제목 (500자+) | 그대로 통과 (cap 미적용) | DB 시점에 잘림/거부. 후속 sanitization issue |
| NULL 등 제어 문자 | 그대로 통과 (sanitization 미적용) | 동상. 회귀 박제는 본 PR 통합 테스트에 포함 |

---

## 5. 테스트 패턴 (Classicist + Testcontainers)

### URL 어댑터 — 통합 테스트

```java
class UrlInputAdapterIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UrlInputAdapter urlInputAdapter;
  @MockBean private ContentExtractService contentExtractService;  // 외부 네트워크 격리

  @Test
  void 사설_IP_URL이면_가드에서_차단되고_외부_fetch는_시도되지_않는다() {
    String privateIp = "http://192.168.1.1/admin";

    assertThatThrownBy(() -> urlInputAdapter.extractFromUrl(privateIp))
        .isInstanceOf(SsrfBlockedException.class);
    verifyNoInteractions(contentExtractService);  // 핵심: fetch 시도 없음 박제
  }
}
```

### Text 어댑터 — 단위 테스트

Spring Context 불필요. `new TextInputAdapter()` 직접 인스턴스화. AssertJ + JUnit 5.

### 필수 테스트 케이스 (어댑터별)

| 케이스 | URL 어댑터 | Text 어댑터 |
|--------|---------|---------|
| happy path | ✅ valid HTTPS → Article | ✅ 멀티라인 → Article |
| invalid input | ✅ ftp:// → BadRequestException | ✅ null/blank → IllegalArgumentException |
| 보안 차단 | ✅ 사설 IP → SsrfBlockedException + verifyNoInteractions | (해당 없음) |
| 외부 실패 | ✅ 타임아웃/5xx → ExtractionFailedException | (해당 없음) |
| edge | ✅ localhost / 127.0.0.1 / 169.254.169.254 / [::1] | ✅ 단일 줄 / 긴 제목 / 제어 문자 |
| source 박제 | ✅ getSource() == URL_INPUT | ✅ getSource() == TEXT_INPUT + getUrl() == null |

---

## 6. BDD 시나리오 작성 가이드

비개발자(교수, PM)도 읽을 수 있도록 **도메인 어휘**로 작성:

| 기술 용어 ❌ | 도메인 어휘 ✅ |
|---|---|
| `BadRequestException`을 던진다 | "잘못된 입력" 신호를 보낸다 |
| `SsrfBlockedException`을 던진다 | "내부 네트워크 차단" 신호를 보낸다 |
| `Article` 도메인 객체 | 기사 카드 |
| invariant | 카드의 최소 요건 |
| ContentExtractService를 호출 | 외부 뉴스 서버에 요청 |

각 시나리오 위에 다음 두 줄을 둔다:
- **왜 이 시나리오가 필요한가** (사용 사례 / 보안 사유 / 정책 사유)
- **이 시나리오가 부품에 요구하는 것** (1, 2, 3 단계)

마지막에 **종합 설계도** (시나리오 1~N을 합쳤을 때의 부품 동작 흐름) 6~10단계 다이어그램.

참고: `docs/md/BDD-url-input-adapter.md`, `docs/md/BDD-text-input-adapter.md`

---

## 7. 후속 어댑터 작성 시 (BE #25 ClaimExtractor 외)

본 패턴은 다음 어댑터들에 동일하게 적용 가능:

| 어댑터 | 입력 | source 후보 | 외부 의존 |
|--------|------|-----------|---------|
| ClaimExtractor (BE #25) | Article body | (Article 자체가 입력) | Gemini API |
| BigKindsAdapter | 검색 키워드 | URL_INPUT (BigKinds URL fetch) | BigKinds API |
| KosisAdapter | 통계 ID | URL_INPUT | KOSIS OpenAPI |
| GdeltAdapter | 키워드 + 기간 | URL_INPUT | GDELT BigQuery |

각 어댑터 작성 시:
1. **시그니처 결정**: 입력 → 출력 도메인 객체
2. **외부 의존 boundary 식별**: HTTP/DB/외부 서비스 (구현체에서 처리)
3. **테스트 시나리오 5건+**: happy / invalid / 보안 / 외부 실패 / edge
4. **BDD 작성**: 도메인 어휘 + 종합 설계도
5. **TDD red → green**: 테스트 먼저, 구현 나중

---

## 8. 참조

- 본 PR: `feat/24-input-adapters-v2` 브랜치 + 정리 PR (closes #24)
- BE #24 이슈: https://github.com/truthscope-smu/truthscope-web-backend/issues/24
- BDD 산출물: `docs/md/BDD-url-input-adapter.md`, `docs/md/BDD-text-input-adapter.md`
- 통합 테스트 패턴: `app/src/test/java/com/truthscope/web/support/AbstractIntegrationTest.java`
- 도메인 모델: `core/src/main/java/com/truthscope/web/entity/Article.java`, `ArticleSource.java`
- SSRF 정책: `app/src/main/java/com/truthscope/web/security/SsrfGuard.java` (BE #20)
- HANDOFF: `truthscope-web/.plans/be24-input-adapters-cleanup/HANDOFF.md`
