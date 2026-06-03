package com.truthscope.web.claim.validation;

import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.Tier3ReasonPolicy;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 한국어 키워드 휴리스틱으로 Tier 3 reason 판정.
 *
 * <p>Tier3ReasonPolicy 의 timeKeywords / outOfScopePatterns 매칭 + missingRefDateThresholdDays 검증. 매칭
 * 시 Optional<Tier3ReasonCandidate> 반환, 매칭 X 시 Optional.empty (SCORABLE 후보).
 *
 * <p>timeKeywords 는 단어 경계 매칭을 적용한다. 한국어는 교착어라 "청년미래적금"·"미래성장" 같은 복합어가 공백 없는 한 어절이므로 단순 contains 로는
 * 키워드 "미래"가 오탐된다(ACL P03-1060). Java 정규식 \\b 는 한글에 무효이므로(\\w=ASCII) 공백·구두점 토큰화 후 token==키워드 또는
 * token이 키워드로 시작하고 나머지가 조사·어미인 경우만 매칭한다(precision 우선). 공백을 포함한 구문형 timeKeyword(예 "증가할 것")는 contains
 * 로 처리한다. outOfScopePatterns 는 어간형 패턴(바람직·평가하면 등) 활용형까지 잡아야 하므로 기존 contains 를 유지한다.
 */
@Component
public class HeuristicValidator {

  private final Tier3ReasonPolicy policy;

  // 공백 + Hangul/영숫자 아닌 문자(구두점·기호)로 어절 분리.
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHangul}\\p{Alnum}]+");

  // 키워드(명사) 뒤에 붙을 수 있는 기능 형태소: 조사 + 서술격(이다)/되다/하다 활용형. 내용 명사(성장·적금 등)는 포함하지
  // 않으므로 "미래성장"·"청년미래적금"의 "미래"는 매칭되지 않고, "예정되어"·"내년에"는 매칭된다.
  private static final Set<String> SUFFIXES =
      Set.of(
          // 조사
          "에",
          "에는",
          "에서",
          "에서는",
          "은",
          "는",
          "이",
          "가",
          "을",
          "를",
          "의",
          "도",
          "만",
          "까지",
          "부터",
          "로",
          "으로",
          "와",
          "과",
          "에게",
          "한테",
          "라도",
          "이라도",
          "이나",
          "나",
          "며",
          "이며",
          "마다",
          "밖에",
          "뿐",
          "보다",
          "처럼",
          "만큼",
          // 이다(서술격) 활용
          "다",
          "이다",
          "인",
          "일",
          "이고",
          "였다",
          "이었다",
          "이라",
          "이라서",
          // 되다 활용
          "되다",
          "된다",
          "되어",
          "되었다",
          "됐다",
          "되며",
          "되는",
          "된",
          "될",
          "되고",
          "되면",
          "되니",
          "됩니다",
          "되어야",
          // 하다 활용
          "하다",
          "한다",
          "하여",
          "했다",
          "하며",
          "하는",
          "한",
          "할",
          "하고",
          "하지",
          "합니다",
          "해",
          "해서",
          "하면");

  public HeuristicValidator(Tier3ReasonPolicy policy) {
    this.policy = policy;
  }

  /**
   * Heuristic 판정.
   *
   * @return Optional.of(reason) = OUT_OF_SCOPE/TIME_SENSITIVE 판정. Optional.empty = SCORABLE 후보.
   */
  public Optional<Tier3ReasonCandidate> validate(ClaimDraft draft) {
    if (draft == null || draft.claimText() == null) {
      return Optional.empty();
    }
    String text = draft.claimText();
    // OUT_OF_SCOPE 패턴 우선 (DISCUSS Q4 정합 - opinion/evaluation/찬반 등). 어간 활용형 보존 위해 contains 유지.
    for (String pattern : policy.outOfScopePatterns()) {
      if (text.contains(pattern)) {
        return Optional.of(Tier3ReasonCandidate.OUT_OF_SCOPE);
      }
    }
    // TIME_SENSITIVE 키워드 매칭 (단어 경계 인식).
    String[] tokens = TOKEN_SPLIT.split(text);
    for (String keyword : policy.timeKeywords()) {
      if (matchesTimeKeyword(text, tokens, keyword)) {
        return Optional.of(Tier3ReasonCandidate.TIME_SENSITIVE);
      }
    }
    return Optional.empty();
  }

  /** 공백 포함 구문형은 contains, 단어형은 어절==키워드 또는 어절==키워드+조사/어미. */
  static boolean matchesTimeKeyword(String text, String[] tokens, String keyword) {
    if (keyword.indexOf(' ') >= 0) {
      return text.contains(keyword);
    }
    for (String token : tokens) {
      if (token.isEmpty()) {
        continue;
      }
      if (token.equals(keyword)) {
        return true;
      }
      if (token.startsWith(keyword) && SUFFIXES.contains(token.substring(keyword.length()))) {
        return true;
      }
    }
    return false;
  }

  public enum Tier3ReasonCandidate {
    OUT_OF_SCOPE,
    TIME_SENSITIVE,
    INSUFFICIENT
  }
}
