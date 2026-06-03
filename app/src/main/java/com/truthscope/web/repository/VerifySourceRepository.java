package com.truthscope.web.repository;

import com.truthscope.web.entity.VerifySource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerifySourceRepository extends JpaRepository<VerifySource, UUID> {

  List<VerifySource> findByResultId(UUID resultId);

  /** 결과 ID 목록으로 출처 목록을 일괄 조회한다 (N+1 방지 bulk 조회). */
  List<VerifySource> findByResultIdIn(List<UUID> resultIds);
}
