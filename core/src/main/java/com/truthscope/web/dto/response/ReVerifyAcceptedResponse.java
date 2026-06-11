package com.truthscope.web.dto.response;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 재검증 접수 응답 DTO */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReVerifyAcceptedResponse {

  /** 재검증 접수된 기존 VerificationResult ID */
  private UUID resultId;

  /** 재검증 대상 Claim ID */
  private UUID claimId;

  /** 접수 상태 — 항상 "ACCEPTED" */
  private String status;
}
