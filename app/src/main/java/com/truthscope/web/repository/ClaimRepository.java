package com.truthscope.web.repository;

import com.truthscope.web.entity.Claim;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

  List<Claim> findByArticleId(UUID articleId);

  @Query(
      "SELECT c FROM Claim c WHERE c.article.id = :articleId ORDER BY c.sortOrder ASC NULLS LAST")
  List<Claim> findByArticleIdOrderBySortOrderAsc(@Param("articleId") UUID articleId);
}
