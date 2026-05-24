package com.truthscope.web.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * XML 격리 prompt template (instruction / user_input 분리 + mini-CoVe 1단계). BE #72 spec. Prompt
 * injection 차단 + CoVe (arxiv 2309.11495) hallucination 감소.
 *
 * <p>template = {@code classpath:policies/prompt-shield-template-v1.xml} (T1-6 sub-agent 가 박제).
 * Placeholder {@code ${ARTICLE_BODY}} 를 사용자 입력으로 치환. XML escape 의무.
 */
@Component
public class PromptShield {

  private static final String TEMPLATE_PATH = "policies/prompt-shield-template-v1.xml";
  private final String template;

  public PromptShield() {
    try {
      ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
      this.template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("PromptShield template 로드 실패: " + TEMPLATE_PATH, e);
    }
  }

  /**
   * 사용자 입력 (Article body) 을 XML 격리 prompt 로 assemble.
   *
   * @param userArticleBody Article 본문 (사용자 입력, XML escape 의무)
   * @return XML 격리된 prompt string
   */
  public String assemble(String userArticleBody) {
    if (userArticleBody == null) {
      throw new IllegalArgumentException("userArticleBody 는 null 일 수 없다");
    }
    // CDATA 안에 있어도 ]]> 는 escape 의무 (XML CDATA 종료 패턴 차단)
    String escaped = userArticleBody.replace("]]>", "]]&gt;");
    return template.replace("${ARTICLE_BODY}", escaped);
  }
}
