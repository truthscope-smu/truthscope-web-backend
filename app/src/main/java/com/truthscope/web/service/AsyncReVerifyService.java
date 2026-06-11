package com.truthscope.web.service;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.exception.NotFoundException;
import com.truthscope.web.repository.ClaimRepository;
import com.truthscope.web.scoring.ClaimCascadeResult;
import com.truthscope.web.scoring.ClaimDraft;
import com.truthscope.web.scoring.ClaimStatusCandidate;
import com.truthscope.web.service.verification.VerificationCascadeService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 재검증 파이프라인 후반부를 비동기로 실행하는 서비스.
 *
 * <p>self-invocation 우회를 위해 ReVerifyService 와 별도 빈으로 분리. AsyncAnalysisService 와 동일한 @Async 패턴을 따른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncReVerifyService {

  private final ClaimRepository claimRepository;
  private final VerificationCascadeService verificationCascadeService;
  private final ReVerifyTransactionService reVerifyTransactionService;

  /**
   * 비동기 재검증 실행.
   *
   * <p>1) Claim 로드 → 2) ClaimDraft 재조립 → 3) cascade 실행 → 4) 결과 영속화.
   *
   * <p>예외 발생 시 기존 결과를 유지(markFailed 없음 — 세션 상태 COMPLETED 유지가 안전 기본값).
   *
   * @param oldResultId 재검증 전 기존 VerificationResult ID
   * @param claimId 재검증 대상 Claim ID
   */
  @Async("analysisExecutor")
  public void reverify(UUID oldResultId, UUID claimId) {
    try {
      // 1. Claim 로드 — 기본 필드(text 등)만 접근하므로 LAZY 문제 없음
      Claim claim =
          claimRepository
              .findById(claimId)
              .orElseThrow(() -> new NotFoundException("Claim 을 찾을 수 없습니다: " + claimId));

      // 2. ClaimDraft 재조립
      ClaimDraft draft =
          new ClaimDraft(
              claim.getId(),
              claim.getText(),
              claim.getSpeakerName(),
              claim.isQuotedClaim(),
              claim.getOriginalContext(),
              ClaimStatusCandidate.SCORABLE,
              null);

      // 3. cascade 실행 — 트랜잭션 밖(외부 HTTP). publishedAt = null(Article 발행일 미영속 — PLAN 결정)
      // cascade 입력 1건에 결과도 1건 반환 보장(stream().map().toList() 구조). 빈 결과는 cascade
      // 내부 예외 시에만 이론상 가능하므로 방어 코드로 조기 반환.
      List<ClaimCascadeResult> results = verificationCascadeService.cascade(List.of(draft), null);
      if (results.isEmpty()) {
        log.error("[AsyncReVerifyService] resultId={} cascade 결과 없음 — 기존 결과 유지", oldResultId);
        return;
      }

      // 4. 결과 영속화
      reVerifyTransactionService.persistReverifyOutcome(oldResultId, results.get(0));
    } catch (RuntimeException ex) {
      log.error("[AsyncReVerifyService] resultId={} 재검증 실패 — 기존 결과 유지", oldResultId, ex);
      // markFailed 없음: 세션 상태 COMPLETED 유지, 기존 결과가 그대로 유효(안전 기본값)
    }
  }
}
