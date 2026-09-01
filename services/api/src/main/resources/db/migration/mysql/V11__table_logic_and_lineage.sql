ALTER TABLE warehouse_tables ADD COLUMN sources JSON NULL;
ALTER TABLE warehouse_tables ADD COLUMN joins JSON NULL;
ALTER TABLE warehouse_tables ADD COLUMN filter_expr TEXT NULL;
ALTER TABLE table_columns ADD COLUMN logic JSON NULL;

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
