package com.truthscope.web.service.audit;

import com.truthscope.web.entity.ApiUsageLog;
import com.truthscope.web.repository.ApiUsageLogRepository;
import jakarta.annotation.Nullable;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 API 호출 로깅 서비스.
 *
 * <p>v1.x sequential cascade 정합 — VerificationCascadeService.cascade() =
 * drafts.stream().map(...).toList() 순차 처리 단계로 동시성 경합 없음. 병렬화 시 별 phase 에서 UPSERT 전략 결정 (PLAN 결정 #30
 * 정합).
 *
 * <p>ApiUsageLog UNIQUE constraint baseline = 없음 (V1__init_schema.sql api_usage_logs 테이블에 UNIQUE
 * constraint 미존재, PRIMARY KEY(id)만 정의). v1.x sequential 전제 단계로 단순 INSERT 사용.
 *
 * <p>entity 필드 타입 정합 — requestCount/tokenCount 는 Integer (entity @Column 기준).
 */
@Service
@Transactional
public class ApiUsageLogService {

  private final ApiUsageLogRepository repository;

  public ApiUsageLogService(ApiUsageLogRepository repository) {
    this.repository = repository;
  }

  /**
   * Gemini API 호출 1건 적재. v1.x sequential 단계로 동시성 보호 불요.
   *
   * @param provider API provider 이름 (예: "GEMINI", "GOOGLE_FACT_CHECK")
   * @param tokenCount 토큰 사용량 (0 이면 unknown)
   */
  public void record(String provider, int tokenCount) {
    record(provider, tokenCount, "SERVER_POOL", null);
  }

  /**
   * BE #74 amend — BYOK 분류 + key fingerprint 기록.
   *
   * @param provider API provider 이름 (예: "GEMINI")
   * @param tokenCount 토큰 사용량 (0 이면 unknown)
   * @param keySource 키 source 분류 — BYOK / SERVER_POOL / BYOK_FAILED / SERVER_POOL_FALLBACK
   * @param keyFingerprint SHA-256 hex 앞 16자 (BYOK 키 사용 시). 서버 키 사용 시 null.
   */
  public void record(
      String provider, int tokenCount, String keySource, @Nullable String keyFingerprint) {
    ApiUsageLog log =
        ApiUsageLog.builder()
            .provider(provider)
            .usageDate(LocalDate.now())
            .requestCount(1)
            .tokenCount(tokenCount)
            .keySource(keySource)
            .keyFingerprint(keyFingerprint)
            .build();
    repository.save(log);
  }
}
