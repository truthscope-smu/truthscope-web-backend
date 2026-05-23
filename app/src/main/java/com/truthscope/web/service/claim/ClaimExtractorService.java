package com.truthscope.web.service.claim;

import com.truthscope.web.dto.response.ExtractedClaim;
import java.util.List;

/**
 * Article 본문에서 사실 주장(claim)을 추출하고 정규화하는 input port.
 *
 * <p>Sprint 3 실구현체는 {@code gemini-3.1-flash-lite-preview} (1순위) / {@code gemini-2.5-flash-lite}
 * (폴백) 모델을 호출한다. {@code gemini-2.0-flash-lite}는 본 프로젝트에서 사용하지 않는다.
 *
 * <p>Sprint 2 현 시점은 contract 확정용 stub({@link ClaimExtractorStubService})만 제공. Sprint 3에서 stub을 실 호출
 * 구현체로 교체할 때 본 인터페이스는 그대로 유지된다.
 *
 * <p>네이밍 규칙: ArchitectureTest serviceNaming 룰이 {@code ..service..} 패키지의 public top-level 클래스에
 * {@code Service} 접미사를 의무화하므로 인터페이스 이름은 {@code ClaimExtractorService}이다 (도메인 어휘는 "ClaimExtractor"지만
 * 코드 식별자는 stereotype 규칙을 우선한다).
 *
 * <p>본 인터페이스는 Phase 55 신뢰도 점수 4함수의 입력 생산자다 — 점수 산식이 요구하는 claim 단위 contract(정규화된 text + importance +
 * sortOrder)를 본 인터페이스가 책임진다.
 */
public interface ClaimExtractorService {

  /**
   * Article 본문에서 claim 목록을 추출한다.
   *
   * <p>Stub은 fixture 고정값을 반환한다. 실구현체는 Gemini API를 호출한다. 본문이 null/blank이면 빈 리스트를 반환한다 (예외를 던지지 않는다 —
   * 빈 본문은 정상 흐름).
   *
   * @param articleBody Article 본문 (null/blank 허용 — 빈 리스트 반환)
   * @return 추출된 claim 목록 (sortOrder 오름차순)
   */
  List<ExtractedClaim> extract(String articleBody);

  /**
   * Claim을 정규화한다. {@code text}의 앞뒤 공백 제거 + 내부 연속 공백을 단일 공백으로 축소 + {@code importance}가 null이면 {@link
   * com.truthscope.web.entity.enums.ClaimImportance#MEDIUM}으로 채운다.
   *
   * <p>정규화 이후 의미가 동일한 두 claim은 {@link ExtractedClaim#equals(Object)}로 같다고 판정된다 — 후속 dedupe 단계는
   * {@code Stream.distinct()} 또는 {@code Set} 자료구조로 가능하다.
   *
   * <p>contract: {@code raw.text}와 {@code raw.sortOrder}는 non-null 의무. {@code raw.importance}만 null
   * 허용(MEDIUM으로 보강). {@code sortOrder}는 정렬 키 의도로 도입됐고 무의미한 기본값이 없으므로 호출자가 명시해야 한다 — Phase 55 점수 산식이
   * 향후 sortOrder를 정렬 키로 사용해도 NPE/잘못된 순서가 발생하지 않도록 strict 정책을 유지한다.
   *
   * @param raw 정규화 전 claim
   * @return 정규화된 claim (입력 객체와 같은 인스턴스가 아닐 수 있다)
   * @throws IllegalArgumentException {@code raw}, {@code raw.text}, 또는 {@code raw.sortOrder}가 null인
   *     경우
   */
  ExtractedClaim normalize(ExtractedClaim raw);
}
