package com.truthscope.web.claim.validation;

import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.Tier3ReasonPolicy;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 한국어 키워드 휴리스틱으로 Tier 3 reason 판정.
 *
 * <p>Tier3ReasonPolicy 의 timeKeywords / outOfScopePatterns 매칭 + missingRefDateThresholdDays 검증. 매칭
 * 시 Optional<Tier3ReasonCandidate> 반환, 매칭 X 시 Optional.empty (SCORABLE 후보).
 */
@Component
public class HeuristicValidator {

  private final Tier3ReasonPolicy policy;

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
    // OUT_OF_SCOPE 패턴 우선 (DISCUSS Q4 정합 - opinion/evaluation/찬반 등)
    for (String pattern : policy.outOfScopePatterns()) {
      if (text.contains(pattern)) {
        return Optional.of(Tier3ReasonCandidate.OUT_OF_SCOPE);
      }
    }
    // TIME_SENSITIVE 키워드 매칭
    for (String keyword : policy.timeKeywords()) {
      if (text.contains(keyword)) {
        return Optional.of(Tier3ReasonCandidate.TIME_SENSITIVE);
      }
    }
    return Optional.empty();
  }

  public enum Tier3ReasonCandidate {
    OUT_OF_SCOPE,
    TIME_SENSITIVE,
    INSUFFICIENT
  }
}
