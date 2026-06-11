package com.truthscope.web.service;

import com.truthscope.web.dto.response.ReVerifyAcceptedResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 재검증 접수 서비스.
 *
 * <p>동기 접수(유효성 검사) 후 비동기 재검증을 트리거한다.
 */
@Service
@RequiredArgsConstructor
public class ReVerifyService {

  private final ReVerifyTransactionService txService;
  private final AsyncReVerifyService asyncReVerifyService;

  /**
   * 재검증 접수: 검증(404/409/429) 통과 시 비동기 재검증 트리거 후 접수 응답 반환.
   *
   * @param resultId 재검증 대상 VerificationResult ID
   * @return 접수 응답 (resultId / claimId / status="ACCEPTED")
   */
  public ReVerifyAcceptedResponse accept(UUID resultId) {
    ReVerifyTransactionService.ReVerifyTarget target = txService.validateAndGet(resultId);
    asyncReVerifyService.reverify(target.resultId(), target.claimId());
    return ReVerifyAcceptedResponse.builder()
        .resultId(target.resultId())
        .claimId(target.claimId())
        .status("ACCEPTED")
        .build();
  }
}
