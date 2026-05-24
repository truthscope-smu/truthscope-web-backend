package com.truthscope.web.claim.validation;

import com.truthscope.web.scoring.ClaimDraft;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Gemini structured output 의 claim_status_candidate 정합 검증.
 *
 * <p>Gemini 가 부여한 status 후보가 SCORABLE 외 (INSUFFICIENT_CANDIDATE / TIME_SENSITIVE_CANDIDATE /
 * OUT_OF_SCOPE_CANDIDATE) 인 경우 해당 Tier3ReasonCandidate 반환. SCORABLE 시 Optional.empty.
 */
@Component
public class GeminiCandidateValidator {

  public Optional<HeuristicValidator.Tier3ReasonCandidate> validate(ClaimDraft draft) {
    if (draft == null || draft.claimStatusCandidate() == null) {
      return Optional.empty();
    }
    return switch (draft.claimStatusCandidate()) {
      case SCORABLE -> Optional.empty();
      case INSUFFICIENT_CANDIDATE ->
          Optional.of(HeuristicValidator.Tier3ReasonCandidate.INSUFFICIENT);
      case TIME_SENSITIVE_CANDIDATE ->
          Optional.of(HeuristicValidator.Tier3ReasonCandidate.TIME_SENSITIVE);
      case OUT_OF_SCOPE_CANDIDATE ->
          Optional.of(HeuristicValidator.Tier3ReasonCandidate.OUT_OF_SCOPE);
    };
  }
}
