package com.truthscope.web.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "api_usage_logs")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ApiUsageLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "member_id")
  private Member member;

  @Column(name = "provider", length = 30)
  private String provider;

  @Column(name = "model", length = 50)
  private String model;

  @Column(name = "usage_date")
  private LocalDate usageDate;

  @Column(name = "request_count")
  private Integer requestCount;

  @Column(name = "token_count")
  private Integer tokenCount;

  @Column(name = "key_source", nullable = false, length = 20)
  private String keySource;

  @Column(name = "key_fingerprint", length = 16)
  private String keyFingerprint;
}
