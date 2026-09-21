-- 不透明 refresh token：只存哈希。access JWT 短寿，refresh 在库里可跨重启换新 access。
CREATE TABLE refresh_tokens (
  id          VARCHAR(64)  PRIMARY KEY,
  user_id     VARCHAR(64)  NOT NULL,
  token_hash  VARCHAR(64)  NOT NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_refresh_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);
