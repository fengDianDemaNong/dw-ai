import type { Column, ModelChatProposal, ModelTableDraft, WarehouseTable } from './types';

export interface ModelChatTurn {
  role: 'user' | 'assistant';
  text: string;
}

export interface ModelChatContext {
  layer: string;
  tables: WarehouseTable[];
  domains: { code: string; name: string }[];
  focusTable?: WarehouseTable | null;
}

const EXTRA_FIELDS: { keys: string[]; cols: Column[] }[] = [
  {
    keys: ['退款', 'refund'],
    cols: [
      { name: 'refund_amt', type: 'DECIMAL(18,2)', comment: '退款金额' },
      { name: 'is_refund', type: 'INT', comment: '是否退款 0/1' },
    ],
  },
  {
    keys: ['优惠', '券', 'coupon'],
    cols: [{ name: 'coupon_amt', type: 'DECIMAL(18,2)', comment: '优惠券抵扣金额' }],
  },
  {
    keys: ['会员', 'member'],
    cols: [
      { name: 'is_member', type: 'INT', comment: '是否会员' },
      { name: 'member_level', type: 'STRING', comment: '会员等级' },
    ],
  },
  {
    keys: ['设备', 'device'],
    cols: [
      { name: 'device_os', type: 'STRING', comment: '操作系统' },
      { name: 'device_brand', type: 'STRING', comment: '设备品牌' },
    ],
  },
];

function col(name: string, type: string, comment: string, extra: Partial<Column> = {}): Column {
  return { name, type, comment, ...extra };
}

function dtCol(): Column {
  return col('dt', 'STRING', '日期分区');
}

function domainCode(ctx: ModelChatContext, text: string): string | undefined {
  const hit = ctx.domains.find((d) => text.includes(d.name) || text.toUpperCase().includes(d.code));
  return hit?.code ?? ctx.domains[0]?.code;
}

function prefix(layer: string) {
  return layer.toLowerCase();
}

function findMentionedTable(text: string, tables: WarehouseTable[]): WarehouseTable | undefined {
  return tables.find((t) => text.includes(t.name) || (t.comment && text.includes(t.comment)));
}

function mergeColumns(base: Column[], extra: Column[]): Column[] {
  const names = new Set(base.map((c) => c.name));
  const out = base.map((c) => ({ ...c }));
  for (const c of extra) {
    if (!names.has(c.name)) {
      out.splice(Math.max(0, out.length - (out.some((x) => x.name === 'dt') ? 1 : 0)), 0, { ...c });
      names.add(c.name);
    }
  }
  return out;
}

function extrasFrom(text: string): Column[] {
  const out: Column[] = [];
  for (const g of EXTRA_FIELDS) {
    if (g.keys.some((k) => text.includes(k))) out.push(...g.cols);
  }
  return out;
}

function entityOf(text: string): 'order' | 'user' | 'item' | 'pay' | 'generic' {
  if (/用户|会员|注册/.test(text)) return 'user';
  if (/商品|SKU|sku|类目/.test(text)) return 'item';
  if (/支付|付款/.test(text)) return 'pay';
  if (/订单|交易|下单/.test(text)) return 'order';
  return 'generic';
}

function createDraft(ctx: ModelChatContext, text: string): ModelTableDraft {
  const layer = ctx.layer;
  const domain = domainCode(ctx, text);
  const entity = entityOf(text);
  const extra = extrasFrom(text);
  const p = prefix(layer);
  const d = (domain ?? 'biz').toLowerCase();
  let name = `${p}_${d}_event_di`;
  let comment = `${layer} 业务表`;
  let grain = '记录';
  let columns: Column[] = [];

  if (layer === 'ODS') {
    if (entity === 'user') {
      name = 'ods_mysql_user_di';
      comment = '用户源系统镜像';
      columns = [
        col('user_id', 'STRING', '用户ID'),
        col('user_name', 'STRING', '昵称', { sensitive: true, grade: 'L3' }),
        col('reg_time', 'DATETIME', '注册时间'),
        dtCol(),
      ];
    } else if (entity === 'item') {
      name = 'ods_mysql_sku_di';
      comment = '商品源系统镜像';
      columns = [
        col('sku_id', 'STRING', 'SKU ID'),
        col('sku_name', 'STRING', '商品名'),
        col('category_id', 'STRING', '类目ID'),
        dtCol(),
      ];
    } else {
      name = 'ods_mysql_order_item_di';
      comment = '订单项源系统镜像';
      columns = [
        col('id', 'BIGINT', '主键'),
        col('order_id', 'STRING', '订单ID'),
        col('sku_id', 'STRING', 'SKU ID'),
        col('user_id', 'STRING', '用户ID'),
        col('pay_amount', 'DECIMAL(18,2)', '实付金额'),
        col('qty', 'INT', '购买数量'),
        col('create_time', 'DATETIME', '下单时间'),
        dtCol(),
      ];
    }
  } else if (layer === 'DWS') {
    name = `${p}_${d}_${entity === 'user' ? 'user' : 'order'}_1d`;
    comment = `${domain ?? '业务'}域按天汇总`;
    grain = '天';
    columns = [
      col('dt', 'STRING', '统计日'),
      col('user_type', 'STRING', '用户类型'),
      col('gmv', 'DECIMAL(18,2)', '支付GMV'),
      col('ord_cnt', 'BIGINT', '订单数'),
      col('pay_user_cnt', 'BIGINT', '支付用户数'),
    ];
  } else if (layer === 'ADS') {
    name = `${p}_rpt_${entity === 'user' ? 'user' : 'gmv'}`;
    comment = '应用层报表';
    grain = '报表行';
    columns = [
      col('dt', 'STRING', '统计日'),
      col('metric_name', 'STRING', '指标名'),
      col('metric_value', 'DECIMAL(18,6)', '指标值'),
    ];
  } else {
    if (entity === 'user') {
      name = `${p}_${d}_user_info_df`;
      comment = `${layer} 用户信息`;
      grain = '用户';
      columns = [
        col('user_id', 'STRING', '用户ID'),
        col('user_type', 'STRING', '用户类型'),
        col('region', 'STRING', '地区'),
        dtCol(),
      ];
    } else if (entity === 'pay') {
      name = `${p}_${d}_pay_detail_di`;
      comment = `${layer} 支付明细`;
      grain = '支付单';
      columns = [
        col('pay_id', 'STRING', '支付单ID'),
        col('order_id', 'STRING', '订单ID'),
        col('pay_amt', 'DECIMAL(18,2)', '支付金额'),
        col('pay_time', 'DATETIME', '支付时间'),
        dtCol(),
      ];
    } else {
      name = `${p}_${d}_order_item_di`;
      comment = `${layer} 订单项明细`;
      grain = '订单项';
      columns = [
        col('order_id', 'STRING', '订单ID'),
        col('item_id', 'STRING', '商品ID'),
        col('user_id', 'STRING', '用户ID'),
        col('order_pay_amt', 'DECIMAL(18,2)', '订单支付金额'),
        col('order_status', 'STRING', '订单状态'),
        dtCol(),
      ];
    }
  }

  columns = mergeColumns(columns, extra);
  const exist = ctx.tables.find((t) => t.name === name);
  return {
    key: exist?.id ?? name,
    mode: exist ? 'update' : 'create',
    tableId: exist?.id,
    layer,
    name: exist ? exist.name : name,
    comment,
    domain,
    grain,
    period: 'di',
    partition: 'dt',
    grade: exist?.grade ?? 'L2',
    columns: exist ? mergeColumns(exist.columns, extra.length ? extra : columns) : columns,
    summary: exist ? `在已有 ${exist.name} 上补字段` : `新建 ${layer} 表 ${name}`,
  };
}

function updateDraft(table: WarehouseTable, text: string): ModelTableDraft {
  const extra = extrasFrom(text);
  const columns = extra.length ? mergeColumns(table.columns, extra) : table.columns.map((c) => ({ ...c }));
  if (!extra.length && /注释|说明/.test(text)) {
    columns.forEach((c) => {
      if (!c.comment) c.comment = c.name;
    });
  }
  return {
    key: table.id,
    mode: 'update',
    tableId: table.id,
    layer: table.layer,
    name: table.name,
    comment: table.comment,
    domain: table.domain,
    grain: table.grain,
    period: table.period,
    partition: table.partition,
    grade: table.grade,
    columns,
    summary: extra.length
      ? `为 ${table.name} 增加 ${extra.map((c) => c.name).join('、')}`
      : `按对话调整 ${table.name} 字段与说明`,
  };
}

export function modelStarters(layer: string, tables: WarehouseTable[]): string[] {
  const first = tables[0];
  const out = [
    `按本层规范设计一张 ${layer} 订单相关表`,
    `设计一张 ${layer} 用户表，说明粒度和主键`,
  ];
  if (first) out.push(`给 ${first.name} 增加退款金额和是否退款字段`);
  out.push('把优惠券抵扣补进现有明细');
  return out;
}

export function replyModelChat(
  text: string,
  ctx: ModelChatContext,
  prev: ModelChatProposal | null
): { text: string; proposal: ModelChatProposal } {
  const mentioned = findMentionedTable(text, ctx.tables);
  const wantUpdate = Boolean(mentioned || ctx.focusTable) && !/新建|另建|再设计一张/.test(text);
  const drafts: ModelTableDraft[] = [];

  if (wantUpdate) {
    const t = mentioned ?? ctx.focusTable;
    if (t) drafts.push(updateDraft(t, text));
  } else {
    drafts.push(createDraft(ctx, text));
  }

  if (prev?.tables.length && /再加一张|另外|同时/.test(text)) {
    const extra = createDraft(ctx, text);
    if (!drafts.some((d) => d.name === extra.name)) drafts.push(extra);
    for (const old of prev.tables) {
      if (!drafts.some((d) => d.key === old.key)) drafts.push(old);
    }
  }

  const proposal: ModelChatProposal = {
    layer: ctx.layer,
    summary: drafts.map((d) => d.summary).join('；'),
    tables: drafts,
  };

  const lines = [
    `这是 ${ctx.layer} 层草案，还不是系统里的表。`,
    ...drafts.map((d) => {
      const verb = d.mode === 'create' ? '新建' : '修改';
      return `· ${verb} \`${d.name}\`（${d.columns.length} 个字段）${d.domain ? ` · 域 ${d.domain}` : ''}${d.grain ? ` · 粒度 ${d.grain}` : ''}`;
    }),
    '右侧勾选后点「生成草稿并写入系统」。想改字段或粒度，直接再说一句。',
  ];
  return { text: lines.join('\n'), proposal };
}
