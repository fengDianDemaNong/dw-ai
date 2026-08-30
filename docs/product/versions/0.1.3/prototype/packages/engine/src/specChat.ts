import type { LayerRule, SpecDomainDraft, SpecProposal, SpecRootDraft } from './types';
import { gradesForIndustry } from './config/grades';
import { defaultLayerPolicy } from './config/layerPolicies';

export interface SpecChatTurn {
  role: 'user' | 'assistant';
  text: string;
}

type Industry = 'ads' | 'ecommerce' | 'finance' | 'content' | 'generic';

const OWNERS = { bizOwner: '待指定', techOwner: '待指定', dataOwner: '待指定' };

function domain(
  code: string,
  name: string,
  definition: string,
  related: string[],
  coreEntities: string[]
): SpecDomainDraft {
  return { code, name, definition, related, coreEntities, ...OWNERS };
}

function root(
  kind: SpecRootDraft['kind'],
  code: string,
  zh: string,
  extra: Partial<SpecRootDraft> = {}
): SpecRootDraft {
  return { kind, code, zh, en: extra.en ?? code, ...extra };
}

function layerRule(
  layer: string,
  naming: string,
  retention: string,
  serve: LayerRule['serve'],
  note: string
): LayerRule {
  return { ...defaultLayerPolicy(layer), layer, naming, retention, serve, note };
}

const STANDARD_LAYERS: LayerRule[] = [
  layerRule('ODS', 'ods_[源系统]_[表名]_[增量标记]', '3-7天', 'forbid', '原始数据，镜像同步，禁止对外服务'),
  layerRule('DWD', 'dwd_[主题域]_[业务过程]_[粒度]_[周期]', '永久', 'forbid', '清洗后明细，维度退化，禁止对外服务'),
  layerRule('DWS', 'dws_[主题域]_[业务过程]_[统计周期]', '永久', 'approval', '轻度汇总，面向分析，审批后可暴露'),
  layerRule('ADS', 'ads_[应用]_[业务场景]', '按需', 'allow', '应用层，直接服务报表与 API'),
];

const ADS_LAYERS: LayerRule[] = [
  layerRule(
    'ODS',
    'ods_[源系统]_[日志名]_[周期]',
    '7-14天',
    'forbid',
    'SSP/DSP/曝光/点击/转化原始日志，按天分区。敏感设备号入库即脱敏。'
  ),
  layerRule(
    'DWD',
    'dwd_[主题域]_[业务过程]_[粒度]_[周期]',
    '永久',
    'forbid',
    '以 search_id 关联的漏斗明细：请求→填充→参竞→曝光→点击→转化。维度退化媒体/DSP/设备。'
  ),
  layerRule(
    'DWS',
    'dws_[主题域]_[指标]_[周期]',
    '永久',
    'approval',
    '1min / 1h / 日三级汇总。高频实时走分钟表，日报走小时/日表，禁止一张大宽表打天下。'
  ),
  layerRule('ADS', 'ads_[应用]_[业务场景]', '按需', 'allow', '投放看板、代码位报表、设备标签等应用表，直接服务。'),
];

const TECH_TIME_ROOTS: SpecRootDraft[] = [
  root('tech', 'id', '唯一标识', { dataType: 'string/bigint' }),
  root('tech', 'cnt', '计数', { dataType: 'bigint' }),
  root('tech', 'amt', '金额', { dataType: 'decimal(18,2)' }),
  root('tech', 'rate', '比率', { dataType: 'decimal(18,6)' }),
  root('tech', 'code', '编码/原因码', { dataType: 'int/string' }),
  root('time', 'dt', '日期分区', { format: 'yyyy-MM-dd' }),
  root('time', 'ts', '时间戳', { format: 'yyyy-MM-dd HH:mm:ss' }),
  root('time', 'hr', '小时', { format: 'yyyy-MM-dd HH' }),
  root('time', 'min', '分钟', { format: 'yyyy-MM-dd HH:mm' }),
];

const ADS_DOMAINS: SpecDomainDraft[] = [
  domain(
    'ADX',
    '广告交易域',
    '覆盖 ADX 竞价交易全链路：SSP 请求、DSP 请求/填充、参竞、曝光、点击、转化。域内用 search_id 贯穿，不按部门拆表。',
    ['MED', 'DMD', 'DEV', 'FIN'],
    ['请求', '填充', '参竞', '曝光', '点击', '转化', 'search_id', 'funnel']
  ),
  domain(
    'MED',
    '媒体供给域',
    '媒体侧供给：seller、代码位、包名、广告位类型。是流量的上游，通过 seller_id / slot_id 与交易域关联。',
    ['ADX'],
    ['媒体', '代码位', '包名', '广告位', 'seller', 'ssp', 'slot']
  ),
  domain(
    'DMD',
    '需求投放域',
    '需求侧投放：DSP、广告主、预算、创意、分发规则。通过 demand_id / advertiser_id / creative_id 关联交易域。',
    ['ADX', 'FIN'],
    ['DSP', '广告主', '预算', '创意', '分发规则', 'demand', 'advertiser']
  ),
  domain(
    'DEV',
    '设备域',
    '设备与环境：OAID/IMEI/IDFA（明文禁止出 ODS）、OS、品牌、网络。作为标准维度被交易域退化引用。',
    ['ADX'],
    ['设备', 'OAID', 'OS', '品牌', '网络', 'device']
  ),
  domain(
    'FIN',
    '计费结算域',
    '价格与利润：媒体底价、DSP 报价/结算价、返点、ADX 利润。金额统一「分/CPM」，禁止和次数混在一张过程表里各算各的。',
    ['ADX', 'DMD'],
    ['底价', '报价', '结算价', '返点', '利润', 'cpm', 'revenue']
  ),
];

const ADS_EXTRA_DOMAINS: Record<string, SpecDomainDraft> = {
  物料: domain('MAT', '物料创意域', '创意物料主数据：图片/视频/落地页/Deeplink，与需求域的 creative_id 对齐。', ['DMD', 'ADX'], ['物料', '创意', '素材', 'creative', 'material']),
  创意: domain('MAT', '物料创意域', '创意物料主数据：图片/视频/落地页/Deeplink，与需求域的 creative_id 对齐。', ['DMD', 'ADX'], ['物料', '创意', '素材', 'creative', 'material']),
  风控: domain('RSK', '反作弊风控域', '异常流量、设备黑名单、点击劫持等风控过程。通过 search_id / device 回写交易域。', ['ADX', 'DEV'], ['反作弊', '黑名单', '异常', 'risk']),
  归因: domain('ATT', '转化归因域', '点击/曝光归因到广告与渠道，服务转化回传与效果对账。', ['ADX', 'DMD'], ['归因', '回传', '转化', 'attribution']),
  用户: domain('USR', '用户域', '媒体用户/账号画像，与设备域互补；能打到账号再上用户域，否则只保留设备域。', ['DEV', 'ADX'], ['用户', '账号', 'user']),
};

const ADS_ROOTS: SpecRootDraft[] = [
  root('biz', 'ssp', '媒体平台', { domain: 'MED' }),
  root('biz', 'dsp', '需求方平台', { domain: 'DMD' }),
  root('biz', 'slot', '代码位', { domain: 'MED', en: 'slot' }),
  root('biz', 'req', '请求', { domain: 'ADX' }),
  root('biz', 'fill', '填充', { domain: 'ADX', formula: 'count(is_fill=1)/count(req)' }),
  root('biz', 'bid', '竞价/报价', { domain: 'ADX' }),
  root('biz', 'win', '胜出', { domain: 'ADX' }),
  root('biz', 'imp', '曝光', { domain: 'ADX' }),
  root('biz', 'clk', '点击', { domain: 'ADX' }),
  root('biz', 'conv', '转化', { domain: 'ADX' }),
  root('biz', 'ctr', '点击率', { domain: 'ADX', formula: 'clk_cnt/imp_cnt', dataType: 'decimal' }),
  root('biz', 'cvr', '转化率', { domain: 'ADX', formula: 'conv_cnt/clk_cnt', dataType: 'decimal' }),
  root('biz', 'cpm', '千次曝光成本', { domain: 'FIN', dataType: 'decimal' }),
  root('biz', 'ecpm', '千次曝光收入', { domain: 'FIN', formula: 'revenue/imp_cnt*1000' }),
  root('biz', 'revenue', '收入/利润', { domain: 'FIN', en: 'revenue' }),
  root('biz', 'rebate', '返点', { domain: 'FIN' }),
  root('biz', 'floor', '底价', { domain: 'FIN' }),
  root('biz', 'advertiser', '广告主', { domain: 'DMD' }),
  root('biz', 'budget', '预算', { domain: 'DMD' }),
  root('biz', 'creative', '创意', { domain: 'DMD' }),
  root('biz', 'device', '设备', { domain: 'DEV' }),
  root('biz', 'oaid', '匿名设备标识', { domain: 'DEV' }),
  root('biz', 'funnel', '漏斗', { domain: 'ADX' }),
  ...TECH_TIME_ROOTS,
];

const ADS_EXTRA_ROOTS: SpecRootDraft[] = [
  root('biz', 'timeout', '超时', { domain: 'ADX' }),
  root('biz', 'nbr', '未出价原因', { domain: 'ADX', en: 'no_bid_reason' }),
  root('biz', 'http', 'HTTP 状态', { domain: 'ADX' }),
  root('biz', 'latency', '耗时', { domain: 'ADX', en: 'cost_time' }),
  root('biz', 'seller', '媒体应用', { domain: 'MED' }),
  root('biz', 'pkg', '包名', { domain: 'MED', en: 'package' }),
  root('biz', 'pid', '广告位', { domain: 'DMD' }),
  root('biz', 'policy', '分发规则', { domain: 'DMD' }),
  root('biz', 'cpc', '点击成本', { domain: 'FIN' }),
  root('biz', 'os', '操作系统', { domain: 'DEV' }),
  root('biz', 'make', '品牌', { domain: 'DEV' }),
  root('biz', 'carrier', '运营商', { domain: 'DEV' }),
];

const ECOM_DOMAINS: SpecDomainDraft[] = [
  domain('TRD', '交易域', '覆盖订单、支付、退款全链路，按业务过程而不是按销售部门划分。', ['USR', 'ITM'], ['订单', '支付', '退款', 'order', 'pay']),
  domain('USR', '用户域', '注册、画像、等级与生命周期。通过 user_id 与交易域关联。', ['TRD'], ['用户', '会员', 'user']),
  domain('ITM', '商品域', '商品、类目、SKU 主数据。通过 item_id / sku_id 关联交易域。', ['TRD'], ['商品', '类目', 'SKU', 'item']),
  domain('MKT', '营销域', '券、活动、渠道投放。作为交易的修饰词，不把券核销塞进订单明细主键。', ['TRD', 'USR'], ['优惠券', '活动', '渠道', 'coupon']),
];

const ECOM_ROOTS: SpecRootDraft[] = [
  root('biz', 'order', '订单', { domain: 'TRD' }),
  root('biz', 'pay', '支付', { domain: 'TRD' }),
  root('biz', 'gmv', '成交金额', { domain: 'TRD', formula: 'sum(order_pay_amt)' }),
  root('biz', 'refund', '退款', { domain: 'TRD' }),
  root('biz', 'user', '用户', { domain: 'USR' }),
  root('biz', 'item', '商品', { domain: 'ITM' }),
  root('biz', 'sku', 'SKU', { domain: 'ITM' }),
  root('biz', 'coupon', '优惠券', { domain: 'MKT' }),
  ...TECH_TIME_ROOTS,
];

const FIN_DOMAINS: SpecDomainDraft[] = [
  domain('ACC', '账户域', '开户、账户状态、余额。是资金过程的核心实体。', ['TXN', 'RSK'], ['账户', '余额', 'account']),
  domain('TXN', '交易域', '充值、转账、提现、支付。按资金业务过程划分。', ['ACC'], ['充值', '转账', '提现', 'txn']),
  domain('RSK', '风控域', '反欺诈、限额、名单。通过 account_id / txn_id 回写。', ['ACC', 'TXN'], ['风控', '限额', '欺诈']),
];

const FIN_ROOTS: SpecRootDraft[] = [
  root('biz', 'acct', '账户', { domain: 'ACC', en: 'account' }),
  root('biz', 'txn', '交易', { domain: 'TXN' }),
  root('biz', 'bal', '余额', { domain: 'ACC', en: 'balance' }),
  root('biz', 'charge', '充值', { domain: 'TXN' }),
  root('biz', 'withdraw', '提现', { domain: 'TXN' }),
  ...TECH_TIME_ROOTS,
];

const CONTENT_DOMAINS: SpecDomainDraft[] = [
  domain('CNT', '内容域', '视频/图文生产与消费：发布、播放、完播、互动。', ['USR', 'CRT'], ['内容', '播放', '完播', 'content']),
  domain('USR', '用户域', '创作者与观众。', ['CNT'], ['用户', '创作者', 'user']),
  domain('CRT', '创作者域', '入驻、分成、稿酬。', ['CNT'], ['创作者', '分成', 'creator']),
];

function detectIndustry(text: string): Industry {
  const t = text.toLowerCase();
  if (/广告|投放|竞价|曝光|adx|dsp|ssp|广告主/.test(t)) return 'ads';
  if (/电商|零售|订单|商品|gmv/.test(t)) return 'ecommerce';
  if (/金融|支付|信贷|账户|银行/.test(t)) return 'finance';
  if (/内容|短视频|直播|播放|创作者/.test(t)) return 'content';
  return 'generic';
}

const INDUSTRY_LABEL: Record<Industry, string> = {
  ads: '广告 / ADX',
  ecommerce: '电商零售',
  finance: '金融支付',
  content: '内容消费',
  generic: '通用',
};

function cloneProposal(p: SpecProposal): SpecProposal {
  return JSON.parse(JSON.stringify(p)) as SpecProposal;
}

function attachGrades(p: SpecProposal): SpecProposal {
  if (!p.grades?.length) p.grades = gradesForIndustry(p.industry);
  return p;
}

function generateGeneric(text: string): SpecProposal {
  const nouns = [...text.matchAll(/([\u4e00-\u9fa5]{2,6})(业务|系统|平台|域|中心)?/g)]
    .map((m) => m[1])
    .filter((n) => !['我们', '帮我', '设计', '整个', '数仓', '规范', '主题', '分层', '词根', '请你'].includes(n));
  const uniq = [...new Set(nouns)].slice(0, 4);
  const domains =
    uniq.length > 0
      ? uniq.map((n, i) =>
          domain(
            n.slice(0, 3).replace(/[^\u4e00-\u9fa5a-zA-Z]/g, '') ? `B${i + 1}${n.length}` : `B${i + 1}`,
            `${n}域`,
            `覆盖「${n}」相关业务过程。请确认核心实体与边界。`,
            [],
            [n]
          )
        )
      : [domain('CORE', '核心业务域', `根据描述「${text.slice(0, 40)}」先建一个核心域，后续再按业务过程拆分。`, [], ['核心实体'])];

  // codes must be ascii
  domains.forEach((d, i) => {
    d.code = ['CORE', 'AUX', 'REF', 'EXT'][i] ?? `D${i + 1}`;
  });

  return {
    industry: 'generic',
    industryLabel: INDUSTRY_LABEL.generic,
    summary: '未识别到成熟行业模板，先按你提到的业务对象给了一版可落地的最小规范，请改域边界后再同步。',
    rationale: [
      '主题域按业务过程划分，避免按部门划分。',
      '先保证 ODS→DWD→DWS→ADS 四层和词根可生成字段名，细节可以在对话里改。',
    ],
    fieldNaming: '[业务过程]_[度量/维度]_[词根]',
    domains,
    layers: STANDARD_LAYERS.map((l) => ({ ...l })),
    roots: [
      root('biz', 'biz', uniq[0] || '业务'),
      ...TECH_TIME_ROOTS,
    ],
    grades: gradesForIndustry('generic'),
  };
}

function generate(industry: Industry, text: string): SpecProposal {
  if (industry === 'ads') {
    const domains = ADS_DOMAINS.map((d) => ({ ...d, coreEntities: [...d.coreEntities], related: [...d.related] }));
    if (/ssp|媒体/.test(text) && !/dsp/.test(text.toLowerCase())) {
      // keep MED
    }
    return {
      industry: 'ads',
      industryLabel: INDUSTRY_LABEL.ads,
      summary:
        '按广告竞价交易的业务过程划分，而不是按 SSP/DSP 两个部门拆仓。交易域吃全链路日志，媒体/需求/设备/结算作为标准维度域与之关联。',
      rationale: [
        'search_id 是跨过程主键，DWD 漏斗明细围绕它建，禁止每个日志一张互不关联的宽表。',
        '金额进结算域（分/CPM），次数进交易域，避免 GMV 式指标和请求量混口径。',
        '设备号明文不出 ODS；DWD 只留哈希或 token。',
        '汇总层按查询粒度建 1min/1h/日，日表可由小时表推导的不重复建。',
      ],
      fieldNaming: '[业务过程]_[度量/维度]_[词根]  例：dsp_fill_cnt、imp_clk_rate、adx_revenue_amt',
      domains,
      layers: ADS_LAYERS.map((l) => ({ ...l })),
      roots: ADS_ROOTS.map((r) => ({ ...r })),
      grades: gradesForIndustry('ads'),
    };
  }
  if (industry === 'ecommerce') {
    return {
      industry: 'ecommerce',
      industryLabel: INDUSTRY_LABEL.ecommerce,
      summary: '交易/用户/商品/营销四域。订单过程进交易域，券和活动作为修饰词进营销域，避免把活动塞进订单主键。',
      rationale: [
        '按业务过程划分：下单、支付、退款在交易域，不按「国内销售部」「海外销售部」拆域。',
        '域之间只通过 user_id、item_id 等标准维度关联。',
      ],
      fieldNaming: '[业务过程]_[度量/维度]_[词根]  例：order_pay_amt、user_reg_cnt',
      domains: ECOM_DOMAINS.map((d) => ({ ...d, related: [...d.related], coreEntities: [...d.coreEntities] })),
      layers: STANDARD_LAYERS.map((l) => ({ ...l })),
      roots: ECOM_ROOTS.map((r) => ({ ...r })),
      grades: gradesForIndustry('ecommerce'),
    };
  }
  if (industry === 'finance') {
    return {
      industry: 'finance',
      industryLabel: INDUSTRY_LABEL.finance,
      summary: '账户、资金交易、风控三域。资金过程与账户状态分开，风控结果回写但不污染交易明细粒度。',
      rationale: ['账户是核心实体，交易是业务过程。', '金额类统一用 amt 词根，比率用 rate。'],
      fieldNaming: '[业务过程]_[度量/维度]_[词根]',
      domains: FIN_DOMAINS.map((d) => ({ ...d, related: [...d.related], coreEntities: [...d.coreEntities] })),
      layers: STANDARD_LAYERS.map((l) => ({ ...l })),
      roots: FIN_ROOTS.map((r) => ({ ...r })),
      grades: gradesForIndustry('finance'),
    };
  }
  if (industry === 'content') {
    return {
      industry: 'content',
      industryLabel: INDUSTRY_LABEL.content,
      summary: '内容消费过程（播放/互动）与创作者分成分开建域，避免把稿费口径写进播放明细。',
      rationale: ['播放是内容域的业务过程。', '分成属于创作者域。'],
      fieldNaming: '[业务过程]_[度量/维度]_[词根]',
      domains: CONTENT_DOMAINS.map((d) => ({ ...d, related: [...d.related], coreEntities: [...d.coreEntities] })),
      layers: STANDARD_LAYERS.map((l) => ({ ...l })),
      roots: [
        root('biz', 'play', '播放', { domain: 'CNT' }),
        root('biz', 'finish', '完播', { domain: 'CNT' }),
        root('biz', 'like', '点赞', { domain: 'CNT' }),
        ...TECH_TIME_ROOTS,
      ],
      grades: gradesForIndustry('content'),
    };
  }
  return generateGeneric(text);
}

function looksLikeFreshDesign(text: string): boolean {
  return /帮我设计|重新设计|推倒|整套规范|整个数仓|我做的是|我们是|从零/.test(text);
}

function findDomain(p: SpecProposal, token: string): SpecDomainDraft | undefined {
  const t = token.replace(/域$/, '').trim();
  return p.domains.find(
    (d) =>
      d.code.toLowerCase() === t.toLowerCase() ||
      d.name.replace(/域$/, '') === t ||
      d.name.includes(t) ||
      d.coreEntities.some((e) => e === t)
  );
}

function dropDomain(p: SpecProposal, hit: SpecDomainDraft, notes: string[]) {
  p.domains = p.domains.filter((d) => d.code !== hit.code);
  p.roots = p.roots.filter((r) => r.domain !== hit.code);
  p.domains.forEach((d) => {
    d.related = d.related.filter((x) => x !== hit.code);
  });
  notes.push(`已从草案中移除主题域 ${hit.code} ${hit.name}，并从词根里拿掉该域条目。`);
}

function applyPatches(prev: SpecProposal, text: string): { proposal: SpecProposal; notes: string[] } {
  const p = cloneProposal(prev);
  const notes: string[] = [];

  const dropRe = /(?:不要|去掉|删除|先不要|去掉一个)\s*([A-Za-z0-9\u4e00-\u9fa5]{1,8}域?)/g;
  let m: RegExpExecArray | null;
  while ((m = dropRe.exec(text))) {
    const hit = findDomain(p, m[1]);
    if (hit) dropDomain(p, hit, notes);
  }
  const dropTail = text.match(/([A-Za-z0-9\u4e00-\u9fa5]{1,8}域)\s*(?:先)?(?:不要|去掉|删掉)/);
  if (dropTail) {
    const hit = findDomain(p, dropTail[1]);
    if (hit && p.domains.some((d) => d.code === hit.code)) dropDomain(p, hit, notes);
  }

  const addRe = /(?:再加|加上|增加|补上)(?:一个)?\s*([A-Za-z0-9\u4e00-\u9fa5]{1,8}域?)/g;
  while ((m = addRe.exec(text))) {
    const token = m[1];
    if (/词根|分层|规范/.test(token)) continue;
    if (/^[a-z0-9]+$/i.test(token) && !/域$/.test(token)) continue;
    const extra = ADS_EXTRA_DOMAINS[token.replace(/域$/, '')];
    if (extra && !p.domains.some((d) => d.code === extra.code)) {
      p.domains.push({ ...extra, related: [...extra.related], coreEntities: [...extra.coreEntities] });
      notes.push(`已加入主题域 ${extra.code} ${extra.name}。`);
      continue;
    }
    if (!findDomain(p, token) && !/词根/.test(token)) {
      const code = token.replace(/域$/, '').slice(0, 3).toUpperCase().replace(/[^A-Z]/g, '') || `X${p.domains.length + 1}`;
      const nd = domain(code, token.endsWith('域') ? token : `${token}域`, `按你的补充新增，请确认业务边界与核心实体。`, [], [token.replace(/域$/, '')]);
      p.domains.push(nd);
      notes.push(`已按你的意见新增主题域 ${nd.code} ${nd.name}，边界还比较粗，建议再说一下核心实体。`);
    }
  }

  if (/词根/.test(text) || /补上|再加上/.test(text)) {
    const bank = p.industry === 'ads' ? [...ADS_ROOTS, ...ADS_EXTRA_ROOTS] : [...ECOM_ROOTS, ...FIN_ROOTS];
    const mentioned = [...text.toLowerCase().matchAll(/[a-z]{2,16}/g)].map((x) => x[0]);
    let added = 0;
    for (const code of mentioned) {
      if (p.roots.some((r) => r.code === code)) continue;
      const hit = bank.find((r) => r.code === code || r.en === code);
      if (hit) {
        p.roots.push({ ...hit });
        added++;
      } else if (!['ods', 'dwd', 'dws', 'ads', 'http'].includes(code)) {
        p.roots.push(root('biz', code, code));
        added++;
      }
    }
    if (p.industry === 'ads' && /再补|多补|补一些|不够/.test(text)) {
      for (const r of ADS_EXTRA_ROOTS) {
        if (!p.roots.some((x) => x.code === r.code)) {
          p.roots.push({ ...r });
          added++;
        }
      }
    }
    if (added) notes.push(`词根库新增 ${added} 条。`);
  }

  const retain = text.match(/ODS.{0,8}(?:保留|留)\s*(\d+)\s*天/i) || text.match(/保留\s*(\d+)\s*天/);
  if (retain) {
    const ods = p.layers.find((l) => l.layer === 'ODS');
    if (ods) {
      ods.retention = `${retain[1]}天`;
      notes.push(`ODS 数据保留已改为 ${ods.retention}。`);
    }
  }

  if (/1\s*分钟|分钟级|1min/.test(text)) {
    const dws = p.layers.find((l) => l.layer === 'DWS');
    if (dws && !dws.note.includes('1min')) {
      dws.note += ' 含 1min 实时汇总。';
      notes.push('DWS 已注明支持 1min 汇总。');
    }
  }

  if (!notes.length) {
    notes.push('已记下这轮意见。若要改某一域的定义，直接说「把 XX 域改成……」；要换行业可以说「重新设计」。');
  }

  p.summary = notes[0];
  return { proposal: p, notes };
}

export const SPEC_STARTERS = [
  '我做的是广告业务，你帮我设计整个数仓规范',
  '广告 ADX，媒体 SSP + 需求 DSP，按漏斗建主题域和词根',
  '我们是电商，订单支付退款都有，出一套主题域和词根',
];

export function replySpecChat(
  userText: string,
  current: SpecProposal | null
): { text: string; proposal: SpecProposal } {
  const text = userText.trim();
  const industry = detectIndustry(text);
  const shouldFresh = !current || looksLikeFreshDesign(text) || (industry !== 'generic' && industry !== current.industry);

  if (shouldFresh) {
    let proposal = attachGrades(generate(industry, text));
    if (/不要|去掉|再加一个|加上一个|保留\s*\d+天/.test(text)) {
      proposal = applyPatches(proposal, text).proposal;
    }
    const domainList = proposal.domains.map((d) => `${d.code} ${d.name}`).join('、');
    const gradeList = (proposal.grades ?? []).map((g) => `${g.code} ${g.name}`).join('、');
    const msg = [
      `按「${proposal.industryLabel}」给你一版数仓规范草案，划分原则是按业务过程，不按部门。`,
      '',
      `主题域（${proposal.domains.length}）：${domainList}`,
      `分层：${proposal.layers.map((l) => l.layer).join(' → ')}`,
      `数据等级：${gradeList || '未设'}`,
      `词根：业务 ${proposal.roots.filter((r) => r.kind === 'biz').length}、技术 ${proposal.roots.filter((r) => r.kind === 'tech').length}、时间 ${proposal.roots.filter((r) => r.kind === 'time').length}`,
      '',
      proposal.rationale.map((r) => `· ${r}`).join('\n'),
      '',
      '右侧勾选条目。可以继续说：「结算域先不要」「再加一个物料域」「ODS 保留 14 天」「词根再补 timeout、nbr」。满意后点「确认同步进系统」。同步写入当前项目，同名编码默认跳过（可勾选覆盖）。空项目上试最干净。',
    ].join('\n');
    return { text: msg, proposal };
  }

  const { proposal, notes } = applyPatches(current, text);
  attachGrades(proposal);
  const msg = [
    notes.join('\n'),
    '',
    `当前草案：主题域 ${proposal.domains.map((d) => d.code).join('、') || '（空）'}；词根 ${proposal.roots.length} 条；等级 ${(proposal.grades ?? []).map((g) => g.code).join('/') || '未设'}。`,
    '继续改，或在右侧勾选后同步。',
  ].join('\n');
  return { text: msg, proposal };
}
