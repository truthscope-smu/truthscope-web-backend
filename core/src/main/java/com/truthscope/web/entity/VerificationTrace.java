package com.truthscope.web.entity;

import com.truthscope.web.entity.enums.DecisionSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
    name = "verification_trace",
    indexes = {
      @Index(name = "idx_trace_result", columnList = "verification_result_id"),
      @Index(name = "idx_trace_created", columnList = "created_at DESC")
    })
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
public class VerificationTrace {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @Column(name = "verification_result_id", nullable = false, columnDefinition = "uuid")
  private UUID verificationResultId;

  @Column(name = "tier", nullable = false)
  private Short tier;

  @Column(name = "adapter_name", nullable = false, columnDefinition = "TEXT")
  private String adapterName;

  @Column(name = "prompt_git_sha", columnDefinition = "TEXT")
  private String promptGitSha;

  @Column(name = "prompt_hash", columnDefinition = "TEXT")
  private String promptHash;

  @Column(name = "model_version", columnDefinition = "TEXT")
  private String modelVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_body", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> requestBody;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> responseBody;

  @Column(name = "duration_ms", nullable = false)
  private Integer durationMs;

  @Column(name = "prompt_version", length = 50)
  private String promptVersion;

  @Column(name = "schema_version", length = 50)
  private String schemaVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision_source", length = 30)
  private DecisionSource decisionSource;

  // @CreationTimestamp는 Hibernate 6 문서상 기본 생성원이 VM (in-memory) 단계로 INSERT 시 JPA가
  // LocalDateTime.now()를 채워 보냄. DDL DEFAULT NOW()는 raw SQL 경로(JPA 외부) fallback.
  // 즉 동시 INSERT 두 건의 created_at은 마이크로초 단위로 분리될 수 있고 UNIQUE 충돌 검증은
  // native query 또는 EntityManager 강제 흐름 필요 (R1-CX10 명확화).
  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
