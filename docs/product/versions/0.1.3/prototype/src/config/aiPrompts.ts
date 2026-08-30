import type { AiPromptSlot } from '../types';

export const AI_PROMPT_PLACEHOLDERS = [
  { key: '{{project}}', desc: '项目名称' },
  { key: '{{domains}}', desc: '主题域摘要' },
  { key: '{{layers}}', desc: '分层摘要' },
  { key: '{{roots}}', desc: '词根摘要' },
  { key: '{{grades}}', desc: '数据等级摘要' },
  { key: '{{layer}}', desc: '当前建模层' },
  { key: '{{focus_table}}', desc: '当前关注表' },
] as const;

export const AI_PROMPT_SLOTS: { slot: AiPromptSlot; label: string; hint: string }[] = [
  { slot: 'spec.system', label: '规范设计', hint: '生成主题域 / 分层 / 等级 / 词根草案时的系统提示' },
  { slot: 'spec.ask.system', label: '规范问答', hint: '只解释已有约定、不写库时的系统提示' },
  { slot: 'model.system', label: '建模设计', hint: '各层对话建表或改表时的系统提示' },
];

export const DEFAULT_AI_PROMPTS: Record<AiPromptSlot, string> = {
  'spec.system': `你是数仓规范设计助手。当前项目：{{project}}。
已有主题域：{{domains}}
已有分层：{{layers}}
已有数据等级：{{grades}}
已有词根：{{roots}}
默认做增量补全，不要无声明覆盖整包分层。每条建议附一句理由。用中文回答。`,
  'spec.ask.system': `你是数仓规范问答助手，只解释本项目已有约定，不产出可写入的草案。
项目：{{project}}
主题域：{{domains}}
分层：{{layers}}
等级：{{grades}}
词根：{{roots}}
用中文简洁回答。`,
  'model.system': `你是数仓建模助手，只设计 {{layer}} 层的表。
项目：{{project}}
主题域：{{domains}}
关注表：{{focus_table}}
命名与分层策略以本项目规范为准。用中文回答，给出表名、字段和一句理由。`,
};
