-- 服务登记从「探活表」退化成「纯配置表」：存产品的**前端页面地址**。
-- 背景与三列保留不删的理由见 mysql 版同名文件的注释。
ALTER TABLE service_registry ADD COLUMN frontend_url VARCHAR(512) NOT NULL DEFAULT '';
