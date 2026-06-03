package com.truthscope.web.service.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truthscope.web.dto.response.ExtractedClaim;
import com.truthscope.web.entity.enums.ClaimImportance;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClaimExtractorStubService 단위 테스트.
 *
 * <p>Spring Context 불필요 — {@code new ClaimExtractorStubService()}로 직접 인스턴스화한다. Sprint 3 실 Gemini
 * 구현체로 교체될 때 본 테스트는 stub 동작 박제로 보존된다 (계약 회귀 감지용).
 */
@DisplayName("ClaimExtractorStubService 단위 테스트")
class ClaimExtractorStubServiceTest {

  private ClaimExtractorService extractor;

  @BeforeEach
  void setUp() {
    extractor = new ClaimExtractorStubService();
  }

  @Nested
  @DisplayName("extract: 본문에서 claim 추출")
  class Extract {

    @Test
    @DisplayName("정상_본문이면_fixture_claim_3건을_sortOrder_오름차순으로_반환한다")
    void 정상_본문이면_fixture_claim_3건을_반환한다() {
      String body = "정부는 2026년 경제성장률 전망치를 발표했다. 전문가들은 수출 회복세를 주요 요인으로 분석했다.";

      List<ExtractedClaim> result = extractor.extract(body);

      assertThat(result).hasSize(3);
      assertThat(result)
          .extracting(ExtractedClaim::getSortOrder)
          .containsExactly((short) 0, (short) 1, (short) 2);
      assertThat(result)
          .extracting(ExtractedClaim::getImportance)
          .containsExactly(ClaimImportance.HIGH, ClaimImportance.MEDIUM, ClaimImportance.LOW);
      assertThat(result).allSatisfy(c -> assertThat(c.getText()).isNotBlank());
    }

    @ParameterizedTest(name = "입력 = [{0}]")
    @DisplayName("null_또는_blank_본문이면_빈_리스트를_반환한다")
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\n", "\t"})
    void null_또는_blank_본문이면_빈_리스트를_반환한다(String emptyBody) {
      List<ExtractedClaim> result = extractor.extract(emptyBody);

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("normalize: claim canonical form 생성")
  class Normalize {

    @Test
    @DisplayName("앞뒤_공백_+_내부_연속_공백이_다른_두_claim은_정규화_후_equals로_동일하다")
    void 앞뒤_공백_내부_공백이_다른_두_claim은_정규화_후_equals로_동일하다() {
      ExtractedClaim raw1 =
          ExtractedClaim.builder()
              .text("  정부는   2026년    경제성장률을   발표했다.  ")
              .importance(ClaimImportance.HIGH)
              .sortOrder((short) 0)
              .build();
      ExtractedClaim raw2 =
          ExtractedClaim.builder()
              .text("정부는 2026년 경제성장률을 발표했다.")
              .importance(ClaimImportance.HIGH)
              .sortOrder((short) 0)
              .build();

      ExtractedClaim n1 = extractor.normalize(raw1);
      ExtractedClaim n2 = extractor.normalize(raw2);

      assertThat(n1.getText()).isEqualTo("정부는 2026년 경제성장률을 발표했다.");
      assertThat(n1).isEqualTo(n2);
      assertThat(List.of(n1, n2).stream().distinct().toList()).hasSize(1);
    }

    @Test
    @DisplayName("importance가_null이면_MEDIUM으로_채워진다")
    void null_importance면_MEDIUM으로_채워진다() {
      ExtractedClaim raw =
          ExtractedClaim.builder().text("주장 본문").importance(null).sortOrder((short) 0).build();

      ExtractedClaim normalized = extractor.normalize(raw);

      assertThat(normalized.getImportance()).isEqualTo(ClaimImportance.MEDIUM);
    }

    @Test
    @DisplayName("raw가_null이면_IllegalArgumentException을_던진다")
    void raw가_null이면_IllegalArgumentException을_던진다() {
      assertThatThrownBy(() -> extractor.normalize(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("raw");
    }

    @Test
    @DisplayName("text가_null이면_IllegalArgumentException을_던진다")
    void text가_null이면_IllegalArgumentException을_던진다() {
      ExtractedClaim raw =
          ExtractedClaim.builder()
              .text(null)
              .importance(ClaimImportance.HIGH)
              .sortOrder((short) 0)
              .build();

      assertThatThrownBy(() -> extractor.normalize(raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("text");
    }

    @Test
    @DisplayName("sortOrder가_null이면_IllegalArgumentException을_던진다")
    void sortOrder가_null이면_IllegalArgumentException을_던진다() {
      ExtractedClaim raw =
          ExtractedClaim.builder()
              .text("주장 본문")
              .importance(ClaimImportance.HIGH)
              .sortOrder(null)
              .build();

      assertThatThrownBy(() -> extractor.normalize(raw))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sortOrder");
    }
  }
}
