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
}
