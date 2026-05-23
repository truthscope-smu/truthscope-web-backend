package com.truthscope.web.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "data_source_snapshots",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_data_source_snapshots_adapter_query_retrieved",
          columnNames = {"adapter_name", "query_hash", "retrieved_at"})
    },
    indexes = {@Index(name = "idx_snapshot_lookup", columnList = "adapter_name, query_hash")})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "JPA Entity는 entity-rules.md상 Lombok @Getter 더하기 @AllArgsConstructor 의무. "
            + "JSONB Map<String, Object>는 Hibernate 6 @JdbcTypeCode(SqlTypes.JSON) 표준 "
            + "매핑 단계로 내부 mutable Map 노출은 JPA dirty checking 정합 필수.")
public class DataSourceSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @Column(name = "adapter_name", nullable = false, columnDefinition = "TEXT")
  private String adapterName;

  @Column(name = "query_hash", nullable = false, columnDefinition = "TEXT")
  private String queryHash;

  @Column(name = "source_version", columnDefinition = "TEXT")
  private String sourceVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> responseBody;

  // @CreationTimestamp는 VM 기본 단계로 retrieved_at는 JPA save 시점 LocalDateTime.now().
  // UNIQUE 충돌 검증은 native query 또는 EntityManager로 동일 retrieved_at 강제 필요.
  @CreationTimestamp
  @Column(name = "retrieved_at", nullable = false, updatable = false)
  private LocalDateTime retrievedAt;
}
