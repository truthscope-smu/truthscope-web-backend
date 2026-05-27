package com.truthscope.web.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BE #72 회귀 시뮬레이션 ABCD — 4 변종 무력화 메타 테스트.
 *
 * <p>각 변종은 PLAN.md T4 섹션에 명시된 코드 변경을 적용하면 실패하는 케이스를 정의한다. 복원 후 모두 GREEN 이어야 함.
 *
 * <p>변종 A: COVE_TEMPLATE_BASE 경로 오타 → loadCoVeTemplate 로드 실패 (IllegaStateException) 변종 B:
 * escapeXmlAttributes '<' escape 제거 → injection payload 차단 실패 변종 C: generate.template
 * ${ARTICLE_BODY} 제거 → placeholder 검증 실패 변종 D: ArchitectureTest promptComponentAccessRule 제거 → 본 메타
 * 테스트 실패
 */
@DisplayName("PromptShield 회귀 시뮬레이션 ABCD 메타 테스트")
class PromptShieldRegressionSimTest {

  /**
   * 변종 D 메타 테스트 — ArchitectureTest 에 promptComponentAccessRule 필드가 존재하는지 확인.
   *
   * <p>변종 D: ArchitectureTest.promptComponentAccessRule 전체 제거 시 본 테스트가 실패한다. 예상 stdout fragment:
   * "expected promptComponentAccessRule ArchTest 필드가 ArchitectureTest 에 존재해야 함 to be true but was
   * false"
   *
   * <p>한계: 필드 존재 여부만 검증. @ArchTest 어노테이션 누락이나 룰 본문 약화는 별도 메커니즘 필요 (본 phase 범위 외).
   */
  @Test
  @DisplayName("variantD_arch_rule_exists — promptComponentAccessRule 필드 ArchitectureTest 에 존재")
  void variantD_arch_rule_exists() throws Exception {
    Class<?> archTestClass = Class.forName("com.truthscope.web.architecture.ArchitectureTest");
    boolean hasRule =
        Arrays.stream(archTestClass.getDeclaredFields())
            .anyMatch(
                f ->
                    f.getName().equals("promptComponentAccessRule")
                        && f.isAnnotationPresent(ArchTest.class)
                        && ArchRule.class.isAssignableFrom(f.getType())
                        && Modifier.isStatic(f.getModifiers()));
    assertThat(hasRule)
        .as("promptComponentAccessRule 필드가 @ArchTest + ArchRule 타입 + static 으로 존재해야 함")
        .isTrue();
  }
}
