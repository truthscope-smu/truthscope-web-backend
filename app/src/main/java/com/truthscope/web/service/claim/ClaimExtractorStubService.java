package com.truthscope.web.service.claim;

import com.truthscope.web.dto.response.ExtractedClaim;
import com.truthscope.web.entity.enums.ClaimImportance;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link ClaimExtractorService}의 Sprint 2 stub 구현.
 *
 * <p>실제 Gemini API 호출 대신 fixture 3건을 반환한다. Sprint 3에서 {@code gemini-3.1-flash-lite-preview} (1순위) /
 * {@code gemini-2.5-flash-lite} (폴백) 호출 구현체로 교체된다. {@code gemini-2.0-flash-lite}는 본 프로젝트에서 사용하지
 * 않는다.
 *
 * <p>stub은 Spring Context에서 단일 빈으로 등록된다. Sprint 3 실구현체 도입 시 본 stub의 {@code @Component}를 제거하거나
 * {@code @Profile("!production")}으로 격리한다.
 */
@Component
public class ClaimExtractorStubService implements ClaimExtractorService {

  private static final List<ExtractedClaim> FIXTURE =
      List.of(
          ExtractedClaim.builder()
              .text("정부는 2026년 경제성장률 전망치를 2.1%로 발표했다.")
              .importance(ClaimImportance.HIGH)
              .sortOrder((short) 0)
              .build(),
          ExtractedClaim.builder()
              .text("전문가들은 수출 회복세가 주요 요인이라고 분석했다.")
              .importance(ClaimImportance.MEDIUM)
              .sortOrder((short) 1)
              .build(),
          ExtractedClaim.builder()
              .text("일부 학계는 글로벌 금리 인하 가능성을 변수로 지적했다.")
              .importance(ClaimImportance.LOW)
              .sortOrder((short) 2)
              .build());

  @Override
  public List<ExtractedClaim> extract(String articleBody) {
    if (articleBody == null || articleBody.isBlank()) {
      return List.of();
    }
    return FIXTURE;
  }

  @Override
  public ExtractedClaim normalize(ExtractedClaim raw) {
    if (raw == null) {
      throw new IllegalArgumentException("raw claim은 null일 수 없습니다");
    }
    if (raw.getText() == null) {
      throw new IllegalArgumentException("claim text는 null일 수 없습니다");
    }
    String canonicalText = raw.getText().trim().replaceAll("\\s+", " ");
    ClaimImportance canonicalImportance =
        raw.getImportance() == null ? ClaimImportance.MEDIUM : raw.getImportance();
    return ExtractedClaim.builder()
        .text(canonicalText)
        .importance(canonicalImportance)
        .sortOrder(raw.getSortOrder())
        .build();
  }
}
