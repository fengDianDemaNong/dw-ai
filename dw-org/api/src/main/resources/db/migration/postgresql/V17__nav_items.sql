-- 门户菜单：平台管理员配置「哪些产品的哪些页面出现在侧栏」。
--
-- 这是「平台提供壳子、把别的服务的页面配进来」那条需求的数据落点。此前侧栏是
-- 前端硬编码的（dw-org/ui/src/config/sysNav.ts），加一个产品的入口要发一次前端版本。
--
-- path 存的是【子应用内的路径】（如 /lineage/tables），不是组织平台的完整路由 ——
-- 完整的 /org/embed/{product}{path} 由前端 buildProductNav 拼出来。
-- 这样菜单项只表达「产品 + 那个产品里的哪一页」，产品换部署地址、换路由前缀都不用改数据。
--
-- product 用的是 service_registry.product 那套产品码（见 ProductCodes.KNOWN）。
-- 它与租户许可里的模块名（tenant_licenses.modules）目前逐字相同，消费面按后者过滤；
-- 万一将来有人加了个许可里不存在的产品码，菜单会【静默不显示】—— 那是最难查的一种坏法，
-- 所以 ProductCodes 有白名单、CrossServiceDesignGuardTest 有守卫。
CREATE TABLE nav_items (
  id         VARCHAR(64)  NOT NULL PRIMARY KEY,
  product    VARCHAR(32)  NOT NULL,
  label      VARCHAR(64)  NOT NULL,
  icon       VARCHAR(32)  NOT NULL DEFAULT '',
  path       VARCHAR(512) NOT NULL DEFAULT '/',
  sort_order INT          NOT NULL DEFAULT 0,
  enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  -- 同一产品下同一个页面配两遍没有意义，只会让侧栏出现两个一模一样的入口
  CONSTRAINT uk_nav_product_path UNIQUE (product, path)
);
