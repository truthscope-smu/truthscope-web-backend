package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truthscope.web.dto.response.ReVerifyAcceptedResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ReVerifyService 단위 테스트. accept 가 target 을 그대로 asyncReVerifyService 에 전달하는지 검증. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReVerifyService 단위 테스트")
class ReVerifyServiceTest {

  @Mock private ReVerifyTransactionService txService;

  @Mock private AsyncReVerifyService asyncReVerifyService;

  @InjectMocks private ReVerifyService reVerifyService;

  @Test
  @DisplayName("accept — validateAndGet 반환값을 asyncReVerifyService.reverify 에 그대로 전달하고 ACCEPTED 응답")
  void accept_delegatesTargetToAsyncService() {
    UUID resultId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    ReVerifyTransactionService.ReVerifyTarget target =
        new ReVerifyTransactionService.ReVerifyTarget(resultId, claimId);
    when(txService.validateAndGet(resultId)).thenReturn(target);

    ReVerifyAcceptedResponse response = reVerifyService.accept(resultId);

    verify(txService).validateAndGet(resultId);
    verify(asyncReVerifyService).reverify(resultId, claimId);
    assertThat(response.getResultId()).isEqualTo(resultId);
    assertThat(response.getClaimId()).isEqualTo(claimId);
    assertThat(response.getStatus()).isEqualTo("ACCEPTED");
  }
}
