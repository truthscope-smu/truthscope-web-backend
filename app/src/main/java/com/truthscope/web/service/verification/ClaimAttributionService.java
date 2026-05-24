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
 * <p>BE #76 + ADR-020 SIFT T (Trace claims) 정합. Tier 2 cascade 가 매 claim 마다 호출하여
 * Gemini 가 추출한 attribution 메타데이터를 영속화한다.
 *
 * <p>µ2.1 단계 = service skeleton. attach() 메서드 본문은 µ2.3 cascade orchestrator 통합 시점에
 * 활성화. 본 단계 = ClaimRepository.findById + null guard 만 박제, setter 호출은 µ2.2 entity ALTER
 * 완료 후 후속 commit 에서 추가 (T2-3 sub-agent 가 setter / builder 박제).
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
    Claim claim = claimRepository.findById(claimId)
        .orElseThrow(() -> new IllegalArgumentException("Claim not found: " + claimId));
    // µ2.3 통합 시점에 setter 호출 (T2-3 entity ALTER 후):
    //   claim.attachSpeaker(speakerName, isQuoted, originalContext);  // business method (no setter)
    // 본 단계 = µ2.1 skeleton, 실제 영속화는 µ2.3 에서 활성화
    claimRepository.save(claim);
  }
}
