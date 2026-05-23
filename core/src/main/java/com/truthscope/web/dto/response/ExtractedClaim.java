package com.truthscope.web.dto.response;

import com.truthscope.web.entity.enums.ClaimImportance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ClaimExtractor가 산출하는 단일 claim 값 객체.
 *
 * <p>{@link com.truthscope.web.entity.Claim} entity의 영속 전 contract 표현이다. id / article 참조 / 감사 필드는
 * 영속 시점에 결정되므로 본 DTO에 없다.
 *
 * <p>{@code equals/hashCode}는 normalize 결과 dedupe에 사용되므로 {@code text} + {@code importance} + {@code
 * sortOrder} 모두 포함한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ExtractedClaim {

  /** Claim 문장 본문 (정규화 후 trim + 내부 공백 단일화) */
  private String text;

  /** Claim 중요도 (HIGH/MEDIUM/LOW) */
  private ClaimImportance importance;

  /** 원문 등장 순서 (0부터 시작) */
  private Short sortOrder;
}
