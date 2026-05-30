package com.truthscope.web.dto.response;

import com.truthscope.web.entity.VerifySource;

/**
 * 검증 출처 증거 응답 DTO (66b T8).
 *
 * <p>VerifySource 엔티티를 직접 노출하지 않고 응답 DTO로 변환한다 (레이어 규칙: Entity 직접 노출 금지). static
 * from(VerifySource)으로 변환한다.
 *
 * @param url 출처 URL
 * @param publisher 발행 기관명
 * @param title 제목
 * @param stance 입장 (supports/refutes/neutral)
 * @param summary 요약
 */
public record EvidenceDto(
    String url, String publisher, String title, String stance, String summary) {

  /** VerifySource 엔티티에서 EvidenceDto로 변환한다. */
  public static EvidenceDto from(VerifySource source) {
    return new EvidenceDto(
        source.getUrl(),
        source.getPublisher(),
        source.getTitle(),
        source.getStance(),
        source.getSummary());
  }
}
