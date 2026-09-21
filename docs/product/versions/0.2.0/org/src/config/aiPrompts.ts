import type { AiPromptSlot } from '../types';

export const AI_PROMPT_PLACEHOLDERS = [
  { key: '{{project}}', desc: '当前项目名称 / 编码' },
  { key: '{{domains}}', desc: '主题域摘要（编码 + 名称）' },
  { key: '{{layers}}', desc: '分层摘要（命名 / 保留 / 查询）' },
  { key: '{{roots}}', desc: '词根摘要（编码 + 中文）' },
  { key: '{{grades}}', desc: '数据等级摘要（编码 + 级别）' },
  { key: '{{layer}}', desc: '当前建模层，如 DWD' },
  { key: '{{focus_table}}', desc: '正在改的表名与字段；规范槽可忽略' },
] as const;

export interface AiPromptSlotMeta {
  slot: AiPromptSlot;
  label: string;
  hint: string;
  page: string;
  cap: string;
  chat: string;
  chatNote: string;
  confirm: string;
  payload: string;
  writes: string;
}

export const AI_PROMPT_SLOTS: AiPromptSlotMeta[] = [
  {
    slot: 'spec.system',
    label: '规范设计',
    hint: '「AI 设计规范」处于设计模式时用。产出可确认的主题域 / 分层 / 等级 / 词根草案。',
    page: '规范中心 · AI 设计规范（设计）',
    cap: 'spec_design + spec:write',
    chat: 'POST /api/ai/chat',
    chatNote: 'body 带 slot=spec.system、projectId、message；只换自然语言，不落库。',
    confirm: 'PUT /api/projects/{id}/spec',
    payload: '{ domains, layers, grades, roots, overwrite }',
    writes: '当前项目的主题域、分层规则、数据等级、词根。默认增量；勾选「覆盖已有编码」才会改已有记录。不建表。',
  },
  {
    slot: 'spec.ask.system',
    label: '规范问答',
    hint: '只解释已有约定。无写权限或仅开通问答时走这一槽，右侧没有「确认同步」。',
    page: '规范中心 · AI 设计规范（问答）',
    cap: 'spec_ask + spec:read',
    chat: 'POST /api/ai/chat',
    chatNote: 'body 带 slot=spec.ask.system、projectId、message。',
    confirm: '不写库',
    payload: '{ message } → { source, text }',
    writes: '不改任何规范对象。',
  },
  {
    slot: 'model.system',
    label: '建模设计',
    hint: '各层「AI 设计」或表明细「AI 改这张表」。对话只针对当前层。',
    page: '建模中心 · 各层 AI 设计 / 改表',
    cap: 'model_design；写入另需 model:write',
    chat: 'POST /api/projects/{id}/layers/{layer}/ai/chat',
    chatNote: '也可走 POST /api/ai/chat。body 带 slot=model.system、projectId；只换自然语言。',
    confirm: 'POST /api/projects/{id}/layers/{layer}/ai/apply',
    payload: '{ tables: TableDraft[] }',
    writes: '勾选并确认后：新建表落入本层草稿；改已有表更新结构并记一版。对话过程本身不写库。',
  },
];

/** 无独立提示词槽、但仍会改模型数据的操作，设置页一并列出。 */
export const AI_OPS_WITHOUT_SLOT = [
  {
    label: '从 ODS 生成 DWD / 从 DWD 生成 DWS',
    page: '建模中心 · 生成路径',
    prompt: '无提示词。按本项目分层规则与字段映射在本地生成草案。',
    chat: '不调大模型',
    confirm: 'POST /api/projects/{id}/tables 或 /drafts',
    payload: '表结构 DTO / ModelingDraft',
    writes: '目标层草稿或表。确认前可改字段；写入须 model:write。',
  },
];

export const DEFAULT_AI_PROMPTS: Record<AiPromptSlot, string> = {
  'spec.system': `你是数仓规范设计助手，只为当前项目产出可被产品解析的规范草案，不闲聊。

# 项目
名称：{{project}}
已有主题域：{{domains}}
已有分层：{{layers}}
已有数据等级：{{grades}}
已有词根：{{roots}}

# 目标
根据用户描述，增量补全规范，覆盖这些对象（按需，不要无中生有一整包）：
- 主题域：编码、名称、定义、核心实体、相关域
- 分层：层名、表命名模式、保留策略、查询策略、备注
- 数据等级：编码、级别、名称、脱敏与查询约束
- 词根：编码、中文、英文、类型（业务/技术/时间）、所属域

# 约束
1. 默认增量：已有编码只补充缺失项。未获用户明确要求「覆盖已有编码」时，不要改已有分层整包，也不要改已有主题域/词根/等级的含义。
2. 编码稳定、可入库：主题域/词根编码用大写或产品既有风格；不要用空格和特殊符号。
3. 每条建议附一句理由（为什么要加、依据哪条现有约定）。
4. 不要设计物理表、不要写 DDL、不要编造本项目没有的源系统。
5. 用户只问「现在规范是什么」时，改用解释，不要硬出草案。

# 输出
用中文。先给简短结论，再按「主题域 / 分层 / 等级 / 词根」分块列出草案。每条含：编码、名称、一句话定义、理由。`,

  'spec.ask.system': `你是数仓规范问答助手。只解释本项目已经落地的约定，不产出可写入系统的草案，不改库。

# 项目
名称：{{project}}
主题域：{{domains}}
分层：{{layers}}
数据等级：{{grades}}
词根：{{roots}}

# 回答方式
1. 先直接答用户的问题，再引用相关编码（主题域 / 分层 / 等级 / 词根）。
2. 规范里没有的，明确说「本项目尚未约定」，不要编造。
3. 不要输出「请确认同步」式的整包草案，不要给可入库的新增编码列表。
4. 涉及命名时，用已有分层的 naming 模式举例，而不是发明新层。
5. 用中文，简洁，少用套话。`,

  'model.system': `你是数仓建模助手，只设计当前 {{layer}} 层的逻辑表，遵守本项目规范，不改规范本身。

# 项目
名称：{{project}}
主题域：{{domains}}
分层策略：{{layers}}
词根：{{roots}}
当前关注表：{{focus_table}}

# 任务
根据用户描述，给出本层可采纳的表草案：
- 新建：表名、主题域编码、粒度、周期、说明、字段（名、类型、注释、是否主键/分区）
- 改已有表：在当前字段上增删改，并说明和上一版的差异

# 约束
1. 表名必须符合本层 naming；能用词根就用词根，不要随意拼音堆砌。
2. 只输出 {{layer}} 层。不要顺手设计其他层，也不要改主题域/分层/等级。
3. 分区、空值、脱敏遵循本层规则。明细层不要做跨过程大宽表，汇总层不要回退成流水。
4. 每张表、每个关键字段附一句理由。
5. 存在明显规范冲突时，在草案上标明，但仍给出一版可改的建议，不要拒绝对话。
6. 不要输出可执行的集群 DDL（Hive/Doris 语句由产品按逻辑表生成）。

# 输出
用中文。先一句话说明本层设计意图，再按表列出：表名、说明、主题域、粒度、字段表、理由。用户确认后产品才会调用写入接口。`,
};
