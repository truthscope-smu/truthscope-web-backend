package com.truthscope.web.service.verification;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.repository.ClaimRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim 의 attribution 3 필드 (speaker_name / is_quoted_claim / original_context) 관리.
 *
 * <p>BE #76 + ADR-020 SIFT T (Trace claims) 정합. Tier 2 cascade 가 매 claim 마다 호출하여 Gemini 가 추출한
 * attribution 메타데이터를 영속화한다.
 *
 * <p>T2-3 entity ALTER 완료(`Claim.attachSpeaker` 비즈니스 메서드)로 attribution 3 필드 실제 영속화 활성화.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ClaimAttributionService {

  private final ClaimRepository claimRepository;

  /**
   * 지정 claim 에 attribution 3 필드를 부착한다.
   *
   * @param claimId 대상 claim PK
   * @param speakerName 발언자 이름 (nullable)
   * @param isQuoted 인용 claim 여부
   * @param originalContext 원문 context (nullable, TEXT)
   */
  public void attach(UUID claimId, String speakerName, boolean isQuoted, String originalContext) {
    Claim claim =
        claimRepository
            .findById(claimId)
            .orElseThrow(() -> new IllegalArgumentException("Claim not found: " + claimId));
    claim.attachSpeaker(speakerName, isQuoted, originalContext);
    claimRepository.save(claim);
  }
}
