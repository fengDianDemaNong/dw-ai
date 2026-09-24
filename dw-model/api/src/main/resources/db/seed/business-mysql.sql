-- 仓建设的建模演示数据（H2 / MySQL）。可重复执行。方言差异见同名 -postgresql 版。
--
-- 前置：identity.sql 已跑过（建出租户 t-xinghe 与项目 p-trade）。
-- multi 模式下本文件是唯一执行的一个 —— 那时身份由组织平台扇出，这里只补业务数据，
-- 项目写死 p-trade，所以必须先对组织平台跑过它的 seed-demo.sh。
--
-- 本文件不含 project_members（那是 members.sql）也不含任何身份表 —— 身份层在 dw-common。

-- 只清本文件自己插入的行，不动别的项目的建模数据。
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

-- `sensitive` 必须加反引号：它是 MySQL 8 的保留字，裸写这条 INSERT 直接语法错。
-- V1__schema.sql 里同一个列也是这么写的（那里还留了说明）。H2 与 PostgreSQL 都不拦，
-- 所以少了反引号只有真 MySQL 会炸 —— 旧 demo-mysql.sql 就是这样错的，一直没被发现。
INSERT INTO table_columns (table_id, name, type, comment, nullable, `sensitive`, grade, enum_values, pos) VALUES
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

-- 列级逻辑与表来源。PG 版要在每个 JSON 字面量后加 ::jsonb，这里靠隐式转换
-- （H2 以 MODE=MySQL 跑，行为与 MySQL 一致）。
UPDATE warehouse_tables SET sources = '[{"tableId":"tbl-ods-order-item","alias":"s"}]' WHERE id = 'tbl-dwd-order-item';
UPDATE warehouse_tables SET sources = '[{"tableId":"tbl-ods-user","alias":"s"}]' WHERE id = 'tbl-dwd-user';
UPDATE warehouse_tables SET sources = '[{"tableId":"tbl-dwd-order-item","alias":"o"}]' WHERE id = 'tbl-dws-order-sum';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"订单标识","sources":[{"alias":"s","column":"order_id"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'order_id';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"来自 sku_id","sources":[{"alias":"s","column":"sku_id"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'item_id';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"下单用户","sources":[{"alias":"s","column":"user_id"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'user_id';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"实付金额","sources":[{"alias":"s","column":"pay_amount"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'order_pay_amt';
UPDATE table_columns SET logic = '{"kind":"transform","desc":"状态码映射为文案","sources":[{"alias":"s","column":"status"}],"expr":"CASE s.status WHEN 1 THEN ''created'' ..."}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'order_status';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"用户类型（关联补齐）","sources":[{"alias":"s","column":"user_id"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'user_type';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"类目","sources":[{"alias":"s","column":"category_id"}]}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'item_category';
UPDATE table_columns SET logic = '{"kind":"constant","desc":"业务日分区","expr":"dt"}' WHERE table_id = 'tbl-dwd-order-item' AND name = 'dt';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"用户标识","sources":[{"alias":"s","column":"user_id"}]}' WHERE table_id = 'tbl-dwd-user' AND name = 'user_id';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"新老客","sources":[{"alias":"s","column":"user_type"}]}' WHERE table_id = 'tbl-dwd-user' AND name = 'user_type';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"地区","sources":[{"alias":"s","column":"region"}]}' WHERE table_id = 'tbl-dwd-user' AND name = 'region';
UPDATE table_columns SET logic = '{"kind":"transform","desc":"注册时间格式化","sources":[{"alias":"s","column":"reg_time"}],"op":"TIME"}' WHERE table_id = 'tbl-dwd-user' AND name = 'reg_ts';
UPDATE table_columns SET logic = '{"kind":"constant","desc":"快照日","expr":"dt"}' WHERE table_id = 'tbl-dwd-user' AND name = 'dt';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"业务日","sources":[{"alias":"o","column":"dt"}]}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'dt';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"新老客","sources":[{"alias":"o","column":"user_type"}]}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'user_type';
UPDATE table_columns SET logic = '{"kind":"passthrough","desc":"类目","sources":[{"alias":"o","column":"item_category"}]}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'item_category';
UPDATE table_columns SET logic = '{"kind":"aggregate","desc":"已支付订单去重计数","sources":[{"alias":"o","column":"order_id"}],"op":"COUNT_DISTINCT","filter":"o.order_status = ''paid''"}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'order_cnt';
UPDATE table_columns SET logic = '{"kind":"aggregate","desc":"已支付订单实付金额之和","sources":[{"alias":"o","column":"order_pay_amt"}],"op":"SUM","filter":"o.order_status = ''paid''"}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'gmv';
UPDATE table_columns SET logic = '{"kind":"aggregate","desc":"支付用户去重","sources":[{"alias":"o","column":"user_id"}],"op":"COUNT_DISTINCT"}' WHERE table_id = 'tbl-dws-order-sum' AND name = 'pay_user_cnt';
