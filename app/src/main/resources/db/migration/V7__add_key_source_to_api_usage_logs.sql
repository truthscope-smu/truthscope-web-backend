-- BE #74 (S3-05) Gemini Claim Extractor BYOK 통합 — ADR-004 §(d) + §(f) 정합
-- key_source: BYOK 사용 분류 (BYOK / SERVER_POOL / BYOK_FAILED / SERVER_POOL_FALLBACK)
-- key_fingerprint: SHA-256 hex digest 앞 16자 (=8바이트). 원문 키 영구 저장 금지

ALTER TABLE api_usage_logs
  ADD COLUMN key_source VARCHAR(20) NOT NULL DEFAULT 'SERVER_POOL',
  ADD COLUMN key_fingerprint VARCHAR(16);

ALTER TABLE api_usage_logs
  ADD CONSTRAINT ck_api_usage_logs_key_source
  CHECK (key_source IN ('BYOK', 'SERVER_POOL', 'BYOK_FAILED', 'SERVER_POOL_FALLBACK'));

-- CodeRabbit reviewfix: BYOK/BYOK_FAILED는 사용자 키 사용이므로 key_fingerprint 16자 NOT NULL,
-- SERVER_POOL/SERVER_POOL_FALLBACK은 서버 기본 키 사용이므로 key_fingerprint NULL (ADR-004 §f 정합).
ALTER TABLE api_usage_logs
  ADD CONSTRAINT ck_api_usage_logs_key_source_fingerprint
  CHECK (
    (key_source IN ('BYOK', 'BYOK_FAILED') AND key_fingerprint IS NOT NULL AND char_length(key_fingerprint) = 16)
    OR (key_source IN ('SERVER_POOL', 'SERVER_POOL_FALLBACK') AND key_fingerprint IS NULL)
  );
