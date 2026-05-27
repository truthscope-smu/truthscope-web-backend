package com.truthscope.web.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * XML 격리 prompt template + CoVe 4-step template 로딩 컴포넌트. BE #72 spec.
 *
 * <p>assemble(String) — 사용자 입력을 XML 격리 prompt 로 조립 (기존 메서드 유지). loadCoVeTemplate(CoVeStep) — CoVe
 * 4-step template 파일을 classpath 에서 로드. escapeXmlAttributes(String) — XML attribute 위치에 삽입 시 필수
 * 5-entity escape.
 */
@Component
public class PromptShield {

  private static final String SHIELD_TEMPLATE_PATH = "policies/prompt-shield-template-v1.xml";
  private static final String COVE_TEMPLATE_BASE = "prompts/cove/";

  private final String template;

  public PromptShield() {
    try {
      ClassPathResource resource = new ClassPathResource(SHIELD_TEMPLATE_PATH);
      try (var is = resource.getInputStream()) {
        this.template = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new IllegalStateException("PromptShield template 로드 실패: " + SHIELD_TEMPLATE_PATH, e);
    }
  }

  /** 기존 메서드 유지 — XML 격리 prompt assemble */
  public String assemble(String userArticleBody) {
    if (userArticleBody == null) {
      throw new IllegalArgumentException("userArticleBody 는 null 일 수 없다");
    }
    String escaped = userArticleBody.replace("]]>", "]]&gt;");
    return template.replace("${ARTICLE_BODY}", escaped);
  }

  /**
   * CoVe 4-step template 파일을 classpath:prompts/cove/{step}.template 에서 로드. orchestration 은 후속
   * phase. 본 메서드는 template 문자열 반환만 담당.
   *
   * @param step CoVe 단계 — GENERATE / PLAN / EXECUTE / VERIFY
   * @return template 문자열 (${CLAIM_TEXT}, ${INITIAL_RESPONSE} 등 placeholder 포함)
   * @throws IllegalStateException template 파일 로드 실패 시
   */
  public String loadCoVeTemplate(CoVeStep step) {
    String path = COVE_TEMPLATE_BASE + step.filename();
    try {
      ClassPathResource resource = new ClassPathResource(path);
      try (var is = resource.getInputStream()) {
        return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new IllegalStateException("CoVe template 로드 실패: " + path, e);
    }
  }

  /**
   * XML attribute 위치에 사용자 입력을 삽입할 때 5-entity escape 적용. CDATA 밖 attribute 위치 전용. assemble() 의 CDATA
   * 방식과 혼용 금지. 이슈 #72 XML escape 요구사항 (&lt; 등) 완전 충족.
   *
   * @param raw 사용자 입력 문자열
   * @return XML-safe 문자열
   */
  public static String escapeXmlAttributes(String raw) {
    if (raw == null) return "";
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  /** CoVe 4-step 열거형. filename() = classpath resource 파일명. */
  public enum CoVeStep {
    GENERATE("generate.template"),
    PLAN("plan.template"),
    EXECUTE("execute.template"),
    VERIFY("verify.template");

    private final String filename;

    CoVeStep(String filename) {
      this.filename = filename;
    }

    public String filename() {
      return filename;
    }
  }
}
