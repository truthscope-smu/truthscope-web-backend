-- BE #74 (S3-05) Gemini Claim Extractor BYOK 통합 — ADR-004 §(d) + §(f) 정합
-- key_source: BYOK 사용 분류 (BYOK / SERVER_POOL / BYOK_FAILED / SERVER_POOL_FALLBACK)
-- key_fingerprint: SHA-256 hex digest 앞 16자 (=8바이트). 원문 키 영구 저장 금지

ALTER TABLE api_usage_logs
  ADD COLUMN key_source VARCHAR(20) NOT NULL DEFAULT 'SERVER_POOL',
  ADD COLUMN key_fingerprint VARCHAR(16);

ALTER TABLE api_usage_logs
  ADD CONSTRAINT ck_api_usage_logs_key_source
  CHECK (key_source IN ('BYOK', 'SERVER_POOL', 'BYOK_FAILED', 'SERVER_POOL_FALLBACK'));
