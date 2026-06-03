package com.truthscope.web.repository;

import com.truthscope.web.dto.projection.AnalysisSessionHistoryRow;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.enums.SessionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisSessionRepository extends JpaRepository<AnalysisSession, UUID> {

  List<AnalysisSession> findByMemberId(UUID memberId);

  List<AnalysisSession> findByStatus(SessionStatus status);

  /**
   * 인증 사용자의 분석 이력을 요청일 내림차순으로 조회한다. article 없는 세션(PENDING)도 좌조인으로 포함되며 article 필드는 null.
   *
   * <p>Hibernate 6(Spring Boot 3.5) ad-hoc join 지원으로 엔티티 조인 사용.
   */
  @Query(
      "select new com.truthscope.web.dto.projection.AnalysisSessionHistoryRow("
          + "s.id, a.id, a.title, a.url, a.domain, s.status, s.totalScore, s.requestedAt, s.completedAt) "
          + "from AnalysisSession s left join Article a on a.session = s "
          + "where s.member.id = :memberId order by s.requestedAt desc")
  List<AnalysisSessionHistoryRow> findHistoryByMemberId(@Param("memberId") UUID memberId);
}
