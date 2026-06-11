package com.truthscope.web.controller;

import com.truthscope.web.dto.response.ReVerifyAcceptedResponse;
import com.truthscope.web.service.ReVerifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 검증 결과 재검증 컨트롤러 */
@RestController
@RequestMapping("/api/v1/verification-results")
@Tag(name = "VerificationResult", description = "검증 결과 재검증 API")
@RequiredArgsConstructor
public class ReVerifyController {

  private final ReVerifyService reVerifyService;

  @Operation(
      summary = "검증 결과 재검증 요청",
      description = "기존 검증 결과를 재검증한다. 쿨다운 및 supersede 상태를 검사 후 비동기 재검증을 트리거한다.")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "재검증 접수됨"),
    @ApiResponse(responseCode = "404", description = "검증 결과 미존재"),
    @ApiResponse(responseCode = "409", description = "이미 정정된 결과 (superseded)"),
    @ApiResponse(responseCode = "429", description = "재검증 쿨다운 중")
  })
  @PostMapping("/{id}/re-verify")
  public ResponseEntity<ReVerifyAcceptedResponse> reVerify(@PathVariable UUID id) {
    return ResponseEntity.accepted().body(reVerifyService.accept(id));
  }
}
