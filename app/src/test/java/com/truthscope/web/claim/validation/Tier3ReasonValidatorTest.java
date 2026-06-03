package com.truthscope.web.claim.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier3ReasonValidator 단위 테스트.
 *
 * <p>HeuristicValidator + GeminiCandidateValidator 를 Mockito mock 으로 격리하여 Validator greater than
 * Gemini priority 규칙 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tier3ReasonValidator 단위 테스트")
class Tier3ReasonValidatorTest {

  @Mock private HeuristicValidator heuristicValidator;
  @Mock private GeminiCandidateValidator geminiCandidateValidator;

  private Tier3ReasonValidator validator;

  private static final ClaimDraft DRAFT =
      new ClaimDraft(
          UUID.randomUUID(),
          "테스트 claim 텍스트",
          null,
          false,
          null,
          ClaimStatusCandidate.SCORABLE,
          null);

  @BeforeEach
  void setUp() {
    validator = new Tier3ReasonValidator(heuristicValidator, geminiCandidateValidator);
  }

  @Test
  @DisplayName("Heuristic_OUT_OF_SCOPE시_Gemini_무시하고_OUT_OF_SCOPE_반환")
  void heuristic_OUT_OF_SCOPE_gemini_무시() {
    when(heuristicValidator.validate(DRAFT))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE));

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE);
    // Heuristic 이 결과를 반환하면 Gemini validator 는 호출되지 않아야 함
    verify(geminiCandidateValidator, never()).validate(DRAFT);
  }

  @Test
  @DisplayName("Heuristic_TIME_SENSITIVE시_Gemini_무시하고_TIME_SENSITIVE_반환")
  void heuristic_TIME_SENSITIVE_gemini_무시() {
    when(heuristicValidator.validate(DRAFT))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE));

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
    verify(geminiCandidateValidator, never()).validate(DRAFT);
  }

  @Test
  @DisplayName("Heuristic_empty_Gemini_INSUFFICIENT_CANDIDATE시_INSUFFICIENT_반환")
  void heuristic_empty_gemini_INSUFFICIENT_사용() {
    when(heuristicValidator.validate(DRAFT)).thenReturn(Optional.empty());
    when(geminiCandidateValidator.validate(DRAFT))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.INSUFFICIENT));

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.INSUFFICIENT);
  }

  @Test
  @DisplayName("Heuristic_empty_Gemini_SCORABLE시_Optional_empty_반환")
  void heuristic_empty_gemini_SCORABLE_empty_반환() {
    when(heuristicValidator.validate(DRAFT)).thenReturn(Optional.empty());
    when(geminiCandidateValidator.validate(DRAFT)).thenReturn(Optional.empty());

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("모두_empty시_Optional_empty_반환_cascade_진입")
  void both_empty_cascade_진입() {
    when(heuristicValidator.validate(DRAFT)).thenReturn(Optional.empty());
    when(geminiCandidateValidator.validate(DRAFT)).thenReturn(Optional.empty());

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Heuristic_empty_Gemini_OUT_OF_SCOPE시_OUT_OF_SCOPE_반환")
  void heuristic_empty_gemini_OUT_OF_SCOPE_반환() {
    when(heuristicValidator.validate(DRAFT)).thenReturn(Optional.empty());
    when(geminiCandidateValidator.validate(DRAFT))
        .thenReturn(Optional.of(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE));

    Optional<HeuristicValidator.Tier3ReasonCandidate> result = validator.validate(DRAFT);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE);
  }
}
