package com.truthscope.web.controller;

import com.truthscope.web.dto.request.AttachToSessionRequest;
import com.truthscope.web.dto.response.ArticleResponse;
import com.truthscope.web.dto.response.ArticleVerificationResponse;
import com.truthscope.web.service.ArticleService;
import com.truthscope.web.service.ArticleVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Article 조회 및 세션 부착 컨트롤러 */
@RestController
@RequestMapping("/api/v1/articles")
@Tag(name = "Article", description = "기사 조회 및 분석 세션 부착 API")
@RequiredArgsConstructor
public class ArticleController {

  private final ArticleService articleService;
  private final ArticleVerificationService articleVerificationService;

  @Operation(summary = "기사 ID로 조회", description = "추출된 기사를 ID로 조회한다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회 성공"),
    @ApiResponse(responseCode = "404", description = "기사 미존재")
  })
  @GetMapping("/{id}")
  public ArticleResponse findById(@PathVariable UUID id) {
    return articleService.findById(id);
  }

  @Operation(summary = "기사를 분석 세션에 부착", description = "1회만 부착 가능. 재부착 시 409 반환.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "부착 성공"),
    @ApiResponse(responseCode = "400", description = "sessionId 누락 또는 invalid UUID"),
    @ApiResponse(responseCode = "404", description = "기사 또는 세션 미존재"),
    @ApiResponse(responseCode = "409", description = "이미 부착된 기사")
  })
  @PostMapping("/{id}/attach")
  public ArticleResponse attachToSession(
      @PathVariable UUID id, @Valid @RequestBody AttachToSessionRequest request) {
    return articleService.attachToSession(id, request.sessionId());
  }

  @Operation(summary = "기사 검증 결과 조회", description = "기사 ID로 분석 검증 결과(점수/판정/claim별 결과)를 조회한다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회 성공"),
    @ApiResponse(responseCode = "404", description = "기사/세션 미존재")
  })
  @GetMapping("/{id}/verification")
  public ArticleVerificationResponse findVerification(@PathVariable UUID id) {
    return articleVerificationService.getVerification(id);
  }
}
