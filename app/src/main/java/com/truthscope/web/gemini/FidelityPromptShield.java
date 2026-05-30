package com.truthscope.web.gemini;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 충실성 판정 XML 격리 prompt template 로딩 컴포넌트.
 *
 * <p>classpath:policies/fidelity-shield-template-v1.xml 을 생성 시점에 로드. assemble(String, String) 으로
 * claimText + candidatesBlock 을 삽입하여 완성된 prompt 문자열 반환. CDATA 구분자 보호: {@code ]]>} → {@code ]]&gt;}
 * (PromptShield 동일 패턴).
 *
 * <p>PromptShield(claim-추출 전용 템플릿) 와 별개 — 직접 재사용 금지.
 */
@Component
public class FidelityPromptShield {

  private static final String TEMPLATE_PATH = "policies/fidelity-shield-template-v1.xml";

  private final String template;

  public FidelityPromptShield() {
    try {
      ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
      try (var is = resource.getInputStream()) {
        this.template = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new IllegalStateException("FidelityPromptShield template 로드 실패: " + TEMPLATE_PATH, e);
    }
  }

  /**
   * claimText 와 candidatesBlock 을 template 에 주입하여 완성된 Gemini prompt 문자열을 반환한다.
   *
   * @param claimText 검증 대상 claim 텍스트 (CDATA 삽입 — {@code ]]>} escape 적용)
   * @param candidatesBlock 후보 목록 렌더링 문자열 (CDATA 삽입 — {@code ]]>} escape 적용)
   * @return 완성된 prompt 문자열
   */
  public String assemble(String claimText, String candidatesBlock) {
    if (claimText == null) {
      throw new IllegalArgumentException("claimText 는 null 일 수 없다");
    }
    if (candidatesBlock == null) {
      throw new IllegalArgumentException("candidatesBlock 은 null 일 수 없다");
    }
    String escapedClaim = claimText.replace("]]>", "]]&gt;");
    String escapedCandidates = candidatesBlock.replace("]]>", "]]&gt;");
    return template
        .replace("${CLAIM_TEXT}", escapedClaim)
        .replace("${CANDIDATES_BLOCK}", escapedCandidates);
  }
}
