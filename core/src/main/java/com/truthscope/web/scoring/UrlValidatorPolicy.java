package com.truthscope.web.scoring;

/**
 * UrlValidator policy. retry는 단순 단발 sleep (Spring Retry / Resilience4j Retry 인스턴스 미도입 — v2 트랙 보존).
 * 직접 Thread.sleep(retryBackoff.toMillis()) 패턴.
 */
public record UrlValidatorPolicy(
    java.time.Duration connectTimeout, // PT5S — JdkClientHttpRequestFactory connect
    java.time.Duration readTimeout, // PT5S — HEAD 응답 read timeout (R2-4 amend)
    int redirectMaxDepth, // 5 — Redirect.NEVER + 수동 Location 검증 + count (CX2-4 amend)
    int retryCount, // 1 — 재시도 1회 (총 2회 시도)
    java.time.Duration retryBackoff // PT1S 고정 — 단발 1초 sleep (지수 backoff X) (R2-7 amend)
    ) {}
