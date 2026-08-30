-- 演示数据（H2 / MySQL）。可重复执行。须已完成 Flyway 建表。
-- 密码：张三/李四/王五 = 123456；admin = admin123

DELETE FROM table_columns WHERE table_id IN (
  'tbl-ods-order-item','tbl-ods-user','tbl-dwd-order-item','tbl-dwd-pay','tbl-dwd-user','tbl-dwd-sku','tbl-dws-order-sum'
);
DELETE FROM warehouse_tables WHERE id IN (
  'tbl-ods-order-item','tbl-ods-user','tbl-dwd-order-item','tbl-dwd-pay','tbl-dwd-user','tbl-dwd-sku','tbl-dws-order-sum'
);
DELETE FROM word_roots WHERE id IN ('r1','r2','r3','r4','r5','r6','r7','r8','r9','r10','r11');
DELETE FROM data_grades WHERE id IN ('g-p-trade-0','g-p-trade-1','g-p-trade-2','g-p-trade-3');
DELETE FROM domains WHERE id IN ('d-trd','d-usr','d-itm');
DELETE FROM layer_rules WHERE project_id IN ('p-trade','p-empty') OR project_id IS NULL;
DELETE FROM project_members WHERE project_id IN ('p-trade','p-empty');
DELETE FROM platform_access WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_grants WHERE id = 'g-xinghe-demo';
DELETE FROM appearance_prefs WHERE (scope = 'platform' AND tenant_id = '') OR tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_llm WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM user_tenants WHERE user_id IN ('张三','李四','王五','admin');
DELETE FROM tenant_licenses WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM projects WHERE id IN ('p-trade','p-empty');
DELETE FROM users WHERE id IN ('张三','李四','王五','admin');
DELETE FROM tenants WHERE id IN ('t-xinghe','t-qihang');

INSERT INTO tenants (id, code, name, owner, status) VALUES
  ('t-xinghe', 'xinghe', '星河电商', '张三', 'active'),
  ('t-qihang', 'qihang', '启航科技', '李四', 'active');

INSERT INTO tenant_licenses (tenant_id, modules) VALUES
  ('t-xinghe', '["warehouse"]'),
  ('t-qihang', '["warehouse"]');

INSERT INTO users (id, username, display_name, password_hash, status, platform_admin) VALUES
  ('admin', 'admin', '平台管理员', '$2y$10$cYrUk2/NqZSloOksxBg1Tu3x8PRLLsxZOvPeR3FfiWuuCgLXTNA1G', 'active', TRUE),
  ('张三', '张三', '张三', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('李四', '李四', '李四', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('王五', '王五', '王五', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE);

INSERT INTO user_tenants (user_id, tenant_id, tenant_role) VALUES
  ('张三', 't-xinghe', 'admin'),
  ('李四', 't-xinghe', 'member'),
  ('李四', 't-qihang', 'admin'),
  ('王五', 't-xinghe', 'member');

INSERT INTO projects (id, tenant_id, code, name, description, owner, created_at) VALUES
  ('p-trade', 't-xinghe', 'trade_dw', '交易数仓', '覆盖订单、支付、退款全链路的主题数仓与指标服务', '张三', '2026-01-12'),
  ('p-empty', 't-qihang', 'default', '默认项目', '新建租户的默认空项目，可在此开始建模', '李四', '2026-08-01');

INSERT INTO project_members (project_id, user_id, role) VALUES
  ('p-trade', '张三', 'admin'),
  ('p-trade', '李四', 'modeler'),
  ('p-empty', '李四', 'admin');

INSERT INTO layer_rules (project_id, layer, naming, retention, serve, note, field_format, time_format, masking, null_handling) VALUES
  (NULL, 'ODS', 'ods_[源系统]_[表名]_[增量标记]', '3-7天', 'forbid', '原始数据，镜像同步', NULL, NULL, 'keep', 'keep'),
  (NULL, 'DWD', 'dwd_[主题域]_[业务过程]_[粒度]_[周期]', '永久', 'forbid', '清洗后明细，维度退化', NULL, NULL, 'mask', 'keep'),
  (NULL, 'DWS', 'dws_[主题域]_[业务过程]_[统计周期]', '永久', 'approval', '轻度汇总，面向分析', NULL, NULL, 'drop', 'keep'),
  (NULL, 'ADS', 'ads_[应用]_[业务场景]', '按需', 'allow', '应用层，直接服务', NULL, NULL, 'keep', 'keep'),
  ('p-trade', 'ODS', 'ods_[源系统]_[表名]_[增量标记]', '3-7天', 'forbid', '原始数据，镜像同步', NULL, NULL, 'keep', 'keep'),
  ('p-trade', 'DWD', 'dwd_[主题域]_[业务过程]_[粒度]_[周期]', '永久', 'forbid', '清洗后明细，维度退化', NULL, NULL, 'mask', 'keep'),
  ('p-trade', 'DWS', 'dws_[主题域]_[业务过程]_[统计周期]', '永久', 'approval', '轻度汇总，面向分析', NULL, NULL, 'drop', 'keep'),
  ('p-trade', 'ADS', 'ads_[应用]_[业务场景]', '按需', 'allow', '应用层，直接服务', NULL, NULL, 'keep', 'keep');

INSERT INTO domains (id, project_id, code, name, definition, biz_owner, tech_owner, data_owner, related, core_entities) VALUES
  ('d-trd', 'p-trade', 'TRD', '交易域', '覆盖订单、支付、退款全链路', '张三', '李四', '王五', '["USR"]', '["订单","支付","退款","order","pay","refund"]'),
  ('d-usr', 'p-trade', 'USR', '用户域', '用户注册、画像、等级与生命周期', '赵六', '李四', '王五', '["TRD"]', '["用户","user","会员"]'),
  ('d-itm', 'p-trade', 'ITM', '商品域', '商品、类目、SKU 主数据', '钱七', '李四', '王五', '["TRD"]', '["商品","item","sku","类目"]');

INSERT INTO data_grades (id, project_id, code, name, level, color, query_policy, export_policy, note, examples) VALUES
  ('g-p-trade-0', 'p-trade', 'L1', '公开', 1, 'green', 'allow', 'allow', '已对外或可公开的统计口径，不含个人与资金明细。', '日活、曝光量、类目 GMV 汇总'),
  ('g-p-trade-1', 'p-trade', 'L2', '内部', 2, 'blue', 'login', 'approval', '企业内部运营数据，登录即可查，导出需审批。', '订单明细、投放计划、代码位报表'),
  ('g-p-trade-2', 'p-trade', 'L3', '敏感', 3, 'orange', 'approval', 'forbid', '含个人标识或设备标识，查询需审批，禁止明文导出。', '手机号、设备号、用户 ID 映射'),
  ('g-p-trade-3', 'p-trade', 'L4', '机密', 4, 'red', 'forbid', 'forbid', '资金账户、密钥、未发布策略。禁止直接查询与导出。', '账户余额、密钥、未发布定价');

INSERT INTO word_roots (id, project_id, kind, code, zh, en, domain, formula, data_type, format) VALUES
  ('r1', 'p-trade', 'biz', 'order', '订单', 'order', 'TRD', NULL, NULL, NULL),
  ('r2', 'p-trade', 'biz', 'pay', '支付', 'pay', 'TRD', NULL, NULL, NULL),
  ('r3', 'p-trade', 'biz', 'gmv', '成交金额', 'gmv', 'TRD', 'sum(order_amount)', NULL, NULL),
  ('r4', 'p-trade', 'biz', 'refund', '退款', 'refund', 'TRD', NULL, NULL, NULL),
  ('r5', 'p-trade', 'biz', 'user', '用户', 'user', 'USR', NULL, NULL, NULL),
  ('r6', 'p-trade', 'biz', 'item', '商品', 'item', 'ITM', NULL, NULL, NULL),
  ('r7', 'p-trade', 'tech', 'id', '唯一标识', 'id', NULL, NULL, 'string/bigint', NULL),
  ('r8', 'p-trade', 'tech', 'cnt', '计数', 'cnt', NULL, NULL, 'bigint', NULL),
  ('r9', 'p-trade', 'tech', 'amt', '金额', 'amt', NULL, NULL, 'decimal(18,2)', NULL),
  ('r10', 'p-trade', 'time', 'dt', '日期分区', 'dt', NULL, NULL, NULL, 'yyyy-MM-dd'),
  ('r11', 'p-trade', 'time', 'ts', '时间戳', 'ts', NULL, NULL, NULL, 'yyyy-MM-dd HH:mm:ss');

INSERT INTO warehouse_tables (id, project_id, layer, name, comment, domain, source_system, grain, period, partition_col, stored_as, status, created_from, grade) VALUES
  ('tbl-ods-order-item', 'p-trade', 'ODS', 'ods_mysql_order_item_di', '订单项原始表（MySQL binlog 同步）', NULL, 'mysql', NULL, 'di', 'dt', NULL, 'published', NULL, 'L2'),
  ('tbl-ods-user', 'p-trade', 'ODS', 'ods_mysql_user_di', '用户原始表', NULL, 'mysql', NULL, 'di', 'dt', NULL, 'published', NULL, 'L3'),
  ('tbl-dwd-order-item', 'p-trade', 'DWD', 'dwd_trd_order_item_di', '交易域-订单项明细', 'TRD', NULL, '订单项', 'di', 'dt', NULL, 'published', 'tbl-ods-order-item', 'L2'),
  ('tbl-dwd-pay', 'p-trade', 'DWD', 'dwd_trd_pay_detail_di', '交易域-支付明细', 'TRD', NULL, '支付单', 'di', 'dt', NULL, 'published', NULL, 'L3'),
  ('tbl-dwd-user', 'p-trade', 'DWD', 'dwd_usr_user_info_df', '用户域-用户信息快照', 'USR', NULL, '用户', 'df', 'dt', NULL, 'published', 'tbl-ods-user', 'L3'),
  ('tbl-dwd-sku', 'p-trade', 'DWD', 'dwd_itm_sku_info_df', '商品域-SKU 主数据', 'ITM', NULL, 'SKU', 'df', 'dt', NULL, 'published', NULL, 'L1'),
  ('tbl-dws-order-sum', 'p-trade', 'DWS', 'dws_trd_order_sum_di', '交易域-订单日汇总', 'TRD', NULL, '日汇总', 'di', 'dt', NULL, 'published', 'tbl-dwd-order-item', 'L1');

INSERT INTO table_columns (table_id, name, type, comment, nullable, sensitive, grade, enum_values, pos) VALUES
  ('tbl-ods-order-item', 'id', 'BIGINT', '主键', NULL, NULL, NULL, NULL, 0),
  ('tbl-ods-order-item', 'order_id', 'STRING', '订单ID', NULL, NULL, NULL, NULL, 1),
  ('tbl-ods-order-item', 'sku_id', 'STRING', 'SKU ID', NULL, NULL, NULL, NULL, 2),
  ('tbl-ods-order-item', 'user_id', 'STRING', '用户ID', NULL, NULL, NULL, NULL, 3),
  ('tbl-ods-order-item', 'pay_amount', 'DECIMAL(18,2)', '实付金额', NULL, NULL, NULL, NULL, 4),
  ('tbl-ods-order-item', 'status', 'INT', '订单状态', NULL, NULL, NULL, '["1","2","3","4","5"]', 5),
  ('tbl-ods-order-item', 'qty', 'INT', '购买数量', NULL, NULL, NULL, NULL, 6),
  ('tbl-ods-order-item', 'category_id', 'STRING', '类目ID', NULL, NULL, NULL, NULL, 7),
  ('tbl-ods-order-item', 'create_time', 'DATETIME', '下单时间', NULL, NULL, NULL, NULL, 8),
  ('tbl-ods-order-item', 'pay_time', 'DATETIME', '支付时间', NULL, NULL, NULL, NULL, 9),
  ('tbl-ods-order-item', 'mobile', 'STRING', '下单手机号', NULL, TRUE, 'L3', NULL, 10),
  ('tbl-ods-order-item', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 11),
  ('tbl-ods-user', 'user_id', 'STRING', '用户ID', NULL, NULL, NULL, NULL, 0),
  ('tbl-ods-user', 'user_name', 'STRING', '昵称', NULL, TRUE, 'L3', NULL, 1),
  ('tbl-ods-user', 'user_type', 'STRING', '用户类型', NULL, NULL, NULL, '["new","old"]', 2),
  ('tbl-ods-user', 'region', 'STRING', '地区', NULL, NULL, NULL, NULL, 3),
  ('tbl-ods-user', 'reg_time', 'DATETIME', '注册时间', NULL, NULL, NULL, NULL, 4),
  ('tbl-ods-user', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 5),
  ('tbl-dwd-order-item', 'order_id', 'STRING', '订单ID', NULL, NULL, NULL, NULL, 0),
  ('tbl-dwd-order-item', 'item_id', 'STRING', '商品ID', NULL, NULL, NULL, NULL, 1),
  ('tbl-dwd-order-item', 'user_id', 'STRING', '用户ID', NULL, NULL, NULL, NULL, 2),
  ('tbl-dwd-order-item', 'order_pay_amt', 'DECIMAL(18,2)', '订单支付金额', NULL, NULL, NULL, NULL, 3),
  ('tbl-dwd-order-item', 'order_status', 'STRING', '订单状态', NULL, NULL, NULL, '["paid","refund","created"]', 4),
  ('tbl-dwd-order-item', 'user_type', 'STRING', '用户类型', NULL, NULL, NULL, NULL, 5),
  ('tbl-dwd-order-item', 'item_category', 'STRING', '商品类目', NULL, NULL, NULL, NULL, 6),
  ('tbl-dwd-order-item', 'region', 'STRING', '地区', NULL, NULL, NULL, NULL, 7),
  ('tbl-dwd-order-item', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 8),
  ('tbl-dwd-pay', 'pay_id', 'STRING', '支付单ID', NULL, NULL, NULL, NULL, 0),
  ('tbl-dwd-pay', 'order_id', 'STRING', '订单ID', NULL, NULL, NULL, NULL, 1),
  ('tbl-dwd-pay', 'user_id', 'STRING', '用户ID', NULL, NULL, NULL, NULL, 2),
  ('tbl-dwd-pay', 'pay_amt', 'DECIMAL(18,2)', '支付金额', NULL, NULL, NULL, NULL, 3),
  ('tbl-dwd-pay', 'pay_channel', 'STRING', '支付渠道', NULL, NULL, NULL, NULL, 4),
  ('tbl-dwd-pay', 'pay_ts', 'STRING', '支付时间', NULL, NULL, NULL, NULL, 5),
  ('tbl-dwd-pay', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 6),
  ('tbl-dwd-user', 'user_id', 'STRING', '用户ID', NULL, NULL, NULL, NULL, 0),
  ('tbl-dwd-user', 'user_type', 'STRING', '用户类型', NULL, NULL, NULL, NULL, 1),
  ('tbl-dwd-user', 'region', 'STRING', '地区', NULL, NULL, NULL, NULL, 2),
  ('tbl-dwd-user', 'reg_ts', 'STRING', '注册时间', NULL, NULL, NULL, NULL, 3),
  ('tbl-dwd-user', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 4),
  ('tbl-dwd-sku', 'item_id', 'STRING', '商品ID', NULL, NULL, NULL, NULL, 0),
  ('tbl-dwd-sku', 'sku_id', 'STRING', 'SKU ID', NULL, NULL, NULL, NULL, 1),
  ('tbl-dwd-sku', 'item_category', 'STRING', '类目', NULL, NULL, NULL, NULL, 2),
  ('tbl-dwd-sku', 'item_name', 'STRING', '商品名', NULL, NULL, NULL, NULL, 3),
  ('tbl-dwd-sku', 'dt', 'STRING', '日期分区', NULL, NULL, NULL, NULL, 4),
  ('tbl-dws-order-sum', 'dt', 'STRING', '日期', NULL, NULL, NULL, NULL, 0),
  ('tbl-dws-order-sum', 'user_type', 'STRING', '用户类型', NULL, NULL, NULL, NULL, 1),
  ('tbl-dws-order-sum', 'item_category', 'STRING', '商品类目', NULL, NULL, NULL, NULL, 2),
  ('tbl-dws-order-sum', 'order_cnt', 'BIGINT', '订单数', NULL, NULL, NULL, NULL, 3),
  ('tbl-dws-order-sum', 'gmv', 'DECIMAL(18,2)', '成交金额', NULL, NULL, NULL, NULL, 4),
  ('tbl-dws-order-sum', 'pay_user_cnt', 'BIGINT', '支付用户数', NULL, NULL, NULL, NULL, 5);

INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos) VALUES
  ('platform', '', 'cyan', 'left'),
  ('tenant', 't-xinghe', 'cyan', 'left'),
  ('tenant', 't-qihang', 'cyan', 'left');

INSERT INTO tenant_grants (id, tenant_id, code, kind, expires_at, created_by, modules, project_ids) VALUES
  ('g-xinghe-demo', 't-xinghe', 'XINGHE-DEMO', 'permanent', NULL, '张三', '[]', '[]');
