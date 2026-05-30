package com.truthscope.web.repository;

import com.truthscope.web.entity.VerificationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {

  Optional<VerificationResult> findByClaimId(UUID claimId);

  /**
   * claim_id 목록에 해당하는 검증 결과를 claim과 함께 단일 쿼리로 조회한다 (H2 N+1 제거).
   *
   * <p>JOIN FETCH로 claim을 함께 로드하므로 호출 측에서 {@code result.getClaim().getId()} 접근 시 LAZY 프록시 초기화 추가
   * 쿼리가 발생하지 않는다. JPQL {@code IN :claimIds}는 빈 컬렉션 파라미터도 안전하게 처리한다(Hibernate 6).
   */
  @Query("SELECT vr FROM VerificationResult vr JOIN FETCH vr.claim WHERE vr.claim.id IN :claimIds")
  List<VerificationResult> findByClaimIdIn(@Param("claimIds") List<UUID> claimIds);
}
