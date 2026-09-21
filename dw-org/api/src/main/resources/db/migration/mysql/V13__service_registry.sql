-- 产品心跳只留最新一行：product 覆盖写，不记流水。
CREATE TABLE service_registry (
  product   VARCHAR(32)  NOT NULL PRIMARY KEY,
  version   VARCHAR(64)  NOT NULL DEFAULT '',
  base_url  VARCHAR(512) NOT NULL,
  seen_at   TIMESTAMP    NOT NULL
);
