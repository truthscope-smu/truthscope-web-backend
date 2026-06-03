package com.truthscope.web.claim.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.scoring.Tier3ReasonPolicy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HeuristicValidator 단위 테스트.
 *
 * <p>Tier3ReasonPolicy 를 직접 생성하여 한국어 키워드 매칭 로직 검증. Spring Context 없이 순수 단위 테스트.
 */
@DisplayName("HeuristicValidator 단위 테스트")
class HeuristicValidatorTest {

  private HeuristicValidator validator;

  private static final Set<String> TIME_KEYWORDS = Set.of("현재", "최근", "오늘", "예정", "진행 중");
  private static final Set<String> OUT_OF_SCOPE_PATTERNS = Set.of("아마도", "생각한다", "의견");

  @BeforeEach
  void setUp() {
    Tier3ReasonPolicy policy = new Tier3ReasonPolicy(TIME_KEYWORDS, OUT_OF_SCOPE_PATTERNS, 30);
    validator = new HeuristicValidator(policy);
  }

  private ClaimDraft buildDraft(String claimText) {
    return new ClaimDraft(
        UUID.randomUUID(), claimText, null, false, null, ClaimStatusCandidate.SCORABLE, null);
  }

  @Test
  @DisplayName("현재_키워드_포함_본문_TIME_SENSITIVE_반환")
  void 현재_키워드_TIME_SENSITIVE_반환() {
    ClaimDraft draft = buildDraft("현재 실업률은 3.2%이다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
  }

  @Test
  @DisplayName("최근_키워드_포함_본문_TIME_SENSITIVE_반환")
  void 최근_키워드_TIME_SENSITIVE_반환() {
    ClaimDraft draft = buildDraft("최근 수출이 급증했다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
  }

  @Test
  @DisplayName("OUT_OF_SCOPE_패턴_포함_본문_OUT_OF_SCOPE_반환")
  void out_of_scope_패턴_OUT_OF_SCOPE_반환() {
    ClaimDraft draft = buildDraft("아마도 경기가 회복될 것 같다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE);
  }

  @Test
  @DisplayName("키워드_없는_본문_empty_반환_SCORABLE_후보")
  void 키워드_없음_empty_반환() {
    ClaimDraft draft = buildDraft("정부는 2025년 GDP 성장률이 3%라고 발표했다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("null_draft_empty_반환")
  void null_draft_empty_반환() {
    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(null);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("OUT_OF_SCOPE_패턴이_TIME_SENSITIVE_키워드보다_우선한다")
  void outOfScope_time_동시_포함_outOfScope_우선() {
    // OUT_OF_SCOPE_패턴 우선 (DISCUSS Q4 정합)
    ClaimDraft draft = buildDraft("아마도 현재 상황이 나아질 것 같다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE);
  }

  @Test
  @DisplayName("예정_키워드_포함_본문_TIME_SENSITIVE_반환")
  void 예정_키워드_TIME_SENSITIVE_반환() {
    ClaimDraft draft = buildDraft("내년 예산 확대가 예정되어 있다.");

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(draft);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
  }

  // --- 단어 경계 매칭 (복합어 부분문자열 오탐 방지) ---

  private HeuristicValidator boundaryValidator() {
    return new HeuristicValidator(new Tier3ReasonPolicy(Set.of("미래", "내년"), Set.of(), 30));
  }

  @Test
  @DisplayName("복합어_청년미래적금_미래성장은_TIME_SENSITIVE_아님_SCORABLE")
  void 복합어_미래_부분문자열_매칭안됨() {
    HeuristicValidator v = boundaryValidator();
    // "미래"가 "청년미래적금"(제도명)·"미래성장"(일반어)의 부분문자열이지만 단어 경계로는 불일치 -> SCORABLE
    assertThat(v.validate(buildDraft("청년미래적금 대상자가 10만명에서 160만명으로 확대된다."))).isEmpty();
    assertThat(v.validate(buildDraft("세출예산의 75%를 미래성장 분야에 배정한다."))).isEmpty();
  }

  @Test
  @DisplayName("내년_조사_활용형_내년에_내년부터_내년_단독_TIME_SENSITIVE")
  void 내년_단어형_조사형_매칭() {
    HeuristicValidator v = boundaryValidator();
    assertThat(v.validate(buildDraft("내년에 제도가 시행된다.")))
        .contains(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
    assertThat(v.validate(buildDraft("내년부터 단가가 인상된다.")))
        .contains(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
    assertThat(v.validate(buildDraft("내년 예산은 727조원이다.")))
        .contains(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
  }

  @Test
  @DisplayName("미래_단독_미래에_되다활용은_TIME_SENSITIVE")
  void 미래_단어형_매칭() {
    HeuristicValidator v = boundaryValidator();
    assertThat(v.validate(buildDraft("미래 세대를 위한 투자다.")))
        .contains(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
    assertThat(v.validate(buildDraft("이 사업은 미래에 시행된다.")))
        .contains(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
  }
}
