-- SQL 血缘分析平台 · 初始数据（MySQL 8.0+）
--
-- 必须在 01_schema.sql 之后执行。内容：
--   1. 默认租户 / 默认项目 —— 请求不带租户头时会落到它们
--   2. 内置「本地元数据目录」元数据源 —— 让 meta_table 参与 SQL 解析
--   3. 结构版本号

-- ---------------------------------------------------------------
-- 三、内置「本地元数据目录」元数据源
--
-- 让 meta_table 作为一种元数据来源参与 SQL 解析，并复用 metadata_source
-- 已有的 priority 机制，这样用户能在「元数据服务」页面上把它和
-- Gravitino / dbx 放在一起排序、启停。base_url 是占位值，不会被真的访问。
-- 该记录不允许删除（Service 层拦截）。
-- ---------------------------------------------------------------

INSERT INTO metadata_source (tenant_id, name, type, base_url, priority, enabled)
VALUES (1, '本地元数据目录', 'CATALOG', 'local://catalog', 50, 1);

-- 默认租户与默认项目（MySQL 8+）
--
-- TenantContextHolder.require() 刻意不提供「默认租户」兜底，因此库里必须先有一条
-- 可用的租户/项目，否则单机部署时任何带租户上下文的接口都无法工作。
-- id 显式写死为 1，TenantInterceptor 在请求未带租户头时会落到这里。

INSERT INTO tenant (id, code, name, status) VALUES (1, 'default', '默认租户', 1);

INSERT INTO project (id, tenant_id, code, name, description, status)
VALUES (1, 1, 'default', '默认项目', '单机部署时的默认项目', 1);

-- 显式插入 id 后要把自增计数器推到 1 之后，否则后续插入会撞主键
ALTER TABLE tenant AUTO_INCREMENT = 2;

ALTER TABLE project AUTO_INCREMENT = 2;

-- 默认数据目录。
-- 本地元数据「不存在无 catalog 的情况」：贴建表语句导入时若没指定目录，就落到这一条。
INSERT INTO data_catalog (tenant_id, project_id, name, is_default, description)
VALUES (1, 1, 'default', 1, '默认数据目录，未指定目录的元数据都归到这里');

-- 结构版本，供应用启动时校验
INSERT INTO schema_version (version, description) VALUES ('1.0.5', '初始版本');
