package com.truthscope.web.repository;

import com.truthscope.web.entity.VerificationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {

  Optional<VerificationResult> findByClaimIdAndSupersededAtIsNull(UUID claimId);

  /**
   * 재검증 대상 결과를 claim 과 함께 조회한다.
   *
   * <p>validateAndGet 에서 사용한다. isCurrent() 검사 + 쿨다운 판정에 claim.id 가 필요하므로 JOIN FETCH 로 한 번에 로드한다.
   */
  @Query("SELECT vr FROM VerificationResult vr JOIN FETCH vr.claim WHERE vr.id = :id")
  Optional<VerificationResult> findWithClaimById(@Param("id") UUID id);

  /**
   * 재검증 persistReverifyOutcome 에서 article · session 체인을 한 번에 로드한다.
   *
   * <p>claim → article → session 경로를 JOIN FETCH 로 미리 로드해 LAZY 초기화 추가 쿼리를 방지한다.
   */
  @Query(
      "SELECT vr FROM VerificationResult vr "
          + "JOIN FETCH vr.claim c "
          + "JOIN FETCH c.article a "
          + "JOIN FETCH a.session "
          + "WHERE vr.id = :id")
  Optional<VerificationResult> findWithChainById(@Param("id") UUID id);

  /**
   * claim_id 목록에 해당하는 현재 검증 결과(superseded_at IS NULL)를 claim과 함께 단일 쿼리로 조회한다 (H2 N+1 제거).
   *
   * <p>JOIN FETCH로 claim을 함께 로드하므로 호출 측에서 {@code result.getClaim().getId()} 접근 시 LAZY 프록시 초기화 추가
   * 쿼리가 발생하지 않는다. JPQL {@code IN :claimIds}는 빈 컬렉션 파라미터도 안전하게 처리한다(Hibernate 6). superseded 행은 제외하고
   * 현재 결과(superseded_at IS NULL)만 반환한다.
   */
  @Query(
      "SELECT vr FROM VerificationResult vr JOIN FETCH vr.claim "
          + "WHERE vr.claim.id IN :claimIds AND vr.supersededAt IS NULL")
  List<VerificationResult> findCurrentByClaimIdIn(@Param("claimIds") List<UUID> claimIds);
}
