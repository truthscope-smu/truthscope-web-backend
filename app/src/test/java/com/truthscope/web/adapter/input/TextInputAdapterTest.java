package com.truthscope.web.adapter.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.ArticleSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TextInputAdapter 단위 테스트 — RED 단계.
 *
 * <p>TextInputAdapter는 아직 미구현 상태이므로 컴파일 오류가 발생하여 RED를 보장한다. Spring Context 불필요 — new
 * TextInputAdapter()로 직접 인스턴스화.
 *
 * <p>설계 결정 (옵션 B): TextInputAdapter는 URL invariant를 우회하기 위해 Article.fromText() 정적 팩토리를 호출한다.
 * Article.fromText()는 Wave 3(P3-B)에서 구현된다.
 *
 * <p>BDD 시나리오 참조: docs/md/BDD-text-input-adapter.md
 */
@DisplayName("TextInputAdapter 단위 테스트")
class TextInputAdapterTest {

  // RED 핵심: TextInputAdapter는 아직 미구현 → 컴파일 오류 발생 (cannot find symbol)
  private TextInputAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new TextInputAdapter();
  }

  // ─────────────────────────────────────────────────
  // Scenario 1: 정상 멀티라인 raw text → Article 반환
  // BDD: Scenario 1
  // ─────────────────────────────────────────────────

  @Nested
  @DisplayName("정상 입력 처리")
  class 정상입력처리 {

    @Test
    @DisplayName("정상_멀티라인_raw_text이면_첫_줄을_제목으로_나머지를_본문으로_Article을_반환한다")
    void 정상_멀티라인_raw_text이면_Article을_반환한다() {
      // Given
      String rawText =
          "정부, 2026년 경제성장률 2.1% 전망 발표\n"
              + "한국 정부는 오늘 기획재정부를 통해 올해 경제성장률 전망치를 2.1%로 발표했다.\n"
              + "전문가들은 수출 회복세가 주요 요인이라고 분석했다.";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).isEqualTo("정부, 2026년 경제성장률 2.1% 전망 발표");
      assertThat(result.getBody())
          .isEqualTo(
              "한국 정부는 오늘 기획재정부를 통해 올해 경제성장률 전망치를 2.1%로 발표했다.\n" + "전문가들은 수출 회복세가 주요 요인이라고 분석했다.");
      assertThat(result.getLang()).isEqualTo("ko");
      // invariant 검증: extractedAt은 null이 아니어야 한다 (Article.fromText()가 설정)
      assertThat(result.getExtractedAt()).isNotNull();
      // source-aware 검증: TEXT_INPUT 입구로 들어왔음이 박제되어야 한다 (D-2 ArticleSource B-light)
      assertThat(result.getSource()).isEqualTo(ArticleSource.TEXT_INPUT);
      // 외부 URL이 없으므로 url은 null이어야 한다 (TEXT_INPUT invariant)
      assertThat(result.getUrl()).isNull();
      // domain은 "user-input" 고정 (Article.fromText 정책)
      assertThat(result.getDomain()).isEqualTo("user-input");
    }

    @Test
    @DisplayName("두_줄_입력이면_첫_줄이_제목이고_두번째_줄이_본문인_Article을_반환한다")
    void 두_줄_입력이면_Article을_반환한다() {
      // Given
      String rawText = "경제 기사 제목\n경제 관련 상세 본문 내용";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).isEqualTo("경제 기사 제목");
      assertThat(result.getBody()).isEqualTo("경제 관련 상세 본문 내용");
    }

    // ─────────────────────────────────────────────────
    // Scenario 4: 단일 줄 입력 → body 빈 문자열로 Article 반환
    // BDD: Scenario 4
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("단일_줄_text이면_제목만_있고_본문이_빈_문자열인_Article을_반환한다")
    void 단일_줄_text이면_Article을_반환한다() {
      // Given: 줄바꿈 없이 한 줄만 입력
      String rawText = "한국 경제 성장률 발표";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).isEqualTo("한국 경제 성장률 발표");
      // 정책 결정: 단일 줄은 유효, body는 빈 문자열
      assertThat(result.getBody()).isEqualTo("");
    }

    // ─────────────────────────────────────────────────
    // Scenario 5: 앞뒤 공백 포함 입력 → 트리밍 후 Article 반환
    // BDD: Scenario 5
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("앞뒤_공백이_포함된_text이면_트리밍하여_Article을_반환한다")
    void 앞뒤_공백이_포함된_text이면_트리밍하여_반환한다() {
      // Given
      String rawText = "  제목 줄\n본문 줄  ";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).isEqualTo("제목 줄");
      assertThat(result.getBody()).isEqualTo("본문 줄");
    }
  }

  // ─────────────────────────────────────────────────
  // Scenario 2 + 3: null / blank text → IllegalArgumentException
  // BDD: Scenario 2, 3
  // ─────────────────────────────────────────────────

  @Nested
  @DisplayName("유효하지 않은 입력 거부")
  class 유효하지않은입력거부 {

    @Test
    @DisplayName("null_text이면_IllegalArgumentException을_던진다")
    void null_text이면_IllegalArgumentException을_던진다() {
      // Given
      String nullText = null;

      // When / Then
      assertThatThrownBy(() -> adapter.extractFromText(nullText))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("text");
    }

    @ParameterizedTest(name = "입력값 = [{0}]")
    @DisplayName("blank_text이면_IllegalArgumentException을_던진다")
    @ValueSource(strings = {" ", "   ", "\t", "\n", "\r\n"})
    void blank_text이면_IllegalArgumentException을_던진다(String blankText) {
      // Given: 공백 문자열은 내용이 없는 입력

      // When / Then
      assertThatThrownBy(() -> adapter.extractFromText(blankText))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("text");
    }

    @ParameterizedTest(name = "입력값 = [{0}]")
    @DisplayName("null_또는_empty_text이면_IllegalArgumentException을_던진다")
    @NullSource
    @ValueSource(strings = {""})
    void null_또는_empty_text이면_IllegalArgumentException을_던진다(String emptyText) {
      // Given: null 또는 빈 문자열

      // When / Then
      assertThatThrownBy(() -> adapter.extractFromText(emptyText))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ─────────────────────────────────────────────────
  // lang heuristic: MVP에서는 "ko" 고정
  // BDD: Scenario 1 lang 항목
  // ─────────────────────────────────────────────────

  @Nested
  @DisplayName("lang 코드 고정")
  class lang코드고정 {

    @Test
    @DisplayName("MVP에서는_lang이_ko로_고정된다")
    void MVP에서는_lang이_ko로_고정된다() {
      // Given: 어떤 텍스트를 입력하더라도 MVP에서 lang은 "ko" 고정
      String rawText = "기사 제목\n기사 본문";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then
      assertThat(result.getLang()).isEqualTo("ko");
    }
  }

  // ─────────────────────────────────────────────────
  // Edge case: codex M3 권고 — 비정상 입력 박제
  // 목적: 현재 정책상 "그대로 통과" 동작을 박제 → 후속 issue에서 cap/sanitization 정책 도입 시 회귀 감지
  // ─────────────────────────────────────────────────

  @Nested
  @DisplayName("비정상 입력 처리 박제")
  class 비정상입력처리박제 {

    @Test
    @DisplayName("매우_긴_제목_500자_초과면_현재_정책상_그대로_통과한다")
    void 매우_긴_제목_500자_초과면_현재_정책상_그대로_통과한다() {
      // Given: title 컬럼 length=500이지만 entity 레벨 검증 없음 (DB 시점에 잘림/거부)
      // 현재 정책 박제: adapter는 length cap 미적용
      String longTitle = "가".repeat(502);
      String rawText = longTitle + "\n본문";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then: 현재는 그대로 통과 (502자). 후속 issue에서 cap 추가 시 본 테스트 갱신 필요
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).hasSize(502);
    }

    @Test
    @DisplayName("제어_문자_NULL_포함_입력은_현재_정책상_그대로_통과한다")
    void 제어_문자_NULL_포함_입력은_현재_정책상_그대로_통과한다() {
      // Given: NULL char(U+0000)는 PostgreSQL이 거부할 수 있는 입력. 현재 어댑터는 sanitization 미적용.
      // Java source에 literal NULL byte는 UB이므로 (char) 0 캐스트로 안전하게 주입 (유니코드 이스케이프 회피).
      char nullChar = (char) 0;
      String rawText = "제목" + nullChar + "줄\n본문" + nullChar + "줄";

      // When
      Article result = adapter.extractFromText(rawText);

      // Then: 현재는 그대로 통과 — title에 NULL 문자 보존 확인. 후속 issue에서 sanitization 추가 시 본 테스트 갱신
      assertThat(result).isNotNull();
      assertThat(result.getTitle()).contains(String.valueOf(nullChar));
    }
  }
}
