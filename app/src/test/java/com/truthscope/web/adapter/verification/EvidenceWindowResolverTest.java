package com.truthscope.web.adapter.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EvidenceWindowResolver 단위 테스트.
 *
 * <p>윈도우 기준일 우선순위 검증: claimText 추출 날짜 우선, 없으면 기사 발행일(fallbackDate), 그것도 없으면 today. window 는 항상
 * [기준일-3, 기준일] (data.go.kr 검색기간 제약 ≤ 3 정합).
 */
@DisplayName("EvidenceWindowResolver 단위 테스트")
class EvidenceWindowResolverTest {

  private final EvidenceWindowResolver resolver = new EvidenceWindowResolver();

  @Test
  @DisplayName("claimText 한국어 날짜 추출 시 해당 날짜 기준 [date-3, date]")
  void 한국어_날짜_추출() {
    EvidenceWindowResolver.Window window = resolver.resolve("2025년 3월 10일 정부가 발표했다.");

    assertThat(window.to()).isEqualTo(LocalDate.of(2025, 3, 10));
    assertThat(window.from()).isEqualTo(LocalDate.of(2025, 3, 7));
  }

  @Test
  @DisplayName("claimText ISO 날짜 추출")
  void iso_날짜_추출() {
    EvidenceWindowResolver.Window window = resolver.resolve("2024-06-15 기준 수치이다.");

    assertThat(window.to()).isEqualTo(LocalDate.of(2024, 6, 15));
    assertThat(window.from()).isEqualTo(LocalDate.of(2024, 6, 12));
  }

  @Test
  @DisplayName("claimText 날짜가 fallbackDate 보다 우선한다")
  void claim_날짜가_fallback보다_우선() {
    EvidenceWindowResolver.Window window =
        resolver.resolve("2025년 1월 5일 발표 내용이다.", LocalDate.of(2025, 12, 1));

    assertThat(window.to()).isEqualTo(LocalDate.of(2025, 1, 5));
    assertThat(window.from()).isEqualTo(LocalDate.of(2025, 1, 2));
  }

  @Test
  @DisplayName("claimText 날짜 없으면 fallbackDate(기사 발행일) 기준")
  void 날짜없으면_fallback_기준() {
    EvidenceWindowResolver.Window window =
        resolver.resolve("정부가 청년 지원을 확대한다.", LocalDate.of(2025, 12, 1));

    assertThat(window.to()).isEqualTo(LocalDate.of(2025, 12, 1));
    assertThat(window.from()).isEqualTo(LocalDate.of(2025, 11, 28));
  }

  @Test
  @DisplayName("claimText 날짜 없고 fallbackDate null 이면 today 기준")
  void 날짜없고_fallback_null이면_today() {
    LocalDate today = LocalDate.now();

    EvidenceWindowResolver.Window window = resolver.resolve("정부가 청년 지원을 확대한다.", null);

    assertThat(window.to()).isEqualTo(today);
    assertThat(window.from()).isEqualTo(today.minusDays(3));
  }

  @Test
  @DisplayName("단일 인자 resolve 는 fallback null 위임 — 날짜 없으면 today")
  void 단일인자_resolve_today() {
    LocalDate today = LocalDate.now();

    EvidenceWindowResolver.Window window = resolver.resolve("날짜 없는 일반 문장이다.");

    assertThat(window.to()).isEqualTo(today);
    assertThat(window.from()).isEqualTo(today.minusDays(3));
  }

  @Test
  @DisplayName("claimText null 이면 fallbackDate 기준")
  void claimText_null이면_fallback() {
    EvidenceWindowResolver.Window window = resolver.resolve(null, LocalDate.of(2025, 5, 8));

    assertThat(window.to()).isEqualTo(LocalDate.of(2025, 5, 8));
    assertThat(window.from()).isEqualTo(LocalDate.of(2025, 5, 5));
  }
}
