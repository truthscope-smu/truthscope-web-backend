package com.truthscope.web.claim.validation;

import com.truthscope.web.scoring.ClaimDraft;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tier 3 reason 판정 orchestrator. **Validator > Gemini priority** (DISCUSS Q4).
 *
 * <p>1) HeuristicValidator (한국어 키워드) 가 OUT_OF_SCOPE / TIME_SENSITIVE 명확 판정 시 사용. 2)
 * HeuristicValidator empty 시 GeminiCandidateValidator (Gemini status 후보) 사용. 3) 모두 empty 시 SCORABLE
 * 후보 (cascade 진입).
 *
 * <p>decisionSource 박제 (rev.2 R1-2):
 *
 * <ul>
 *   <li>Heuristic 가 override 시 VALIDATOR_OVERRIDE (Gemini candidate 가 다른 값이었던 경우)
 *   <li>Heuristic 가 Gemini candidate 와 동일 시 HEURISTIC_FALLBACK
 *   <li>Heuristic empty + Gemini 사용 시 GEMINI
 * </ul>
 */
@Component
public class Tier3ReasonValidator {

  private final HeuristicValidator heuristicValidator;
  private final GeminiCandidateValidator geminiCandidateValidator;

  public Tier3ReasonValidator(
      HeuristicValidator heuristicValidator, GeminiCandidateValidator geminiCandidateValidator) {
    this.heuristicValidator = heuristicValidator;
    this.geminiCandidateValidator = geminiCandidateValidator;
  }

  /**
   * @return Optional.of(reason) = Tier 3 진입. Optional.empty = SCORABLE 후보 (cascade Tier 1/2 진입)
   */
  public Optional<HeuristicValidator.Tier3ReasonCandidate> validate(ClaimDraft draft) {
    Optional<HeuristicValidator.Tier3ReasonCandidate> heuristicResult =
        heuristicValidator.validate(draft);
    if (heuristicResult.isPresent()) {
      // Validator > Gemini priority - 휴리스틱 매칭 시 무조건 휴리스틱 결과 사용
      return heuristicResult;
    }
    // Heuristic empty 시 Gemini candidate fallback
    return geminiCandidateValidator.validate(draft);
  }
}
