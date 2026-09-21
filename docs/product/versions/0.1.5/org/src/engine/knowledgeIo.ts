import type { EngineKind, KnowledgeSection, TenantKnowledgeArticle } from '../types';
import { ALL_ENGINES, engineLabel } from '../config/knowledge';
import { downloadBlob } from './specIo';

export const KNOWLEDGE_PACK_KIND = 'dw-ai.knowledge';
export const KNOWLEDGE_PACK_VERSION = 1;

export interface KnowledgePackArticle {
  engine: EngineKind;
  id?: string;
  title: string;
  summary?: string;
  body?: string;
  sourceUrl?: string;
  sourceLabel?: string;
  sections?: KnowledgeSection[];
  notes?: string[];
  sql?: string;
  sqlCaption?: string;
}

export interface KnowledgePack {
  kind: typeof KNOWLEDGE_PACK_KIND;
  version: number;
  articles: KnowledgePackArticle[];
}

export interface KnowledgeParseResult {
  articles: KnowledgePackArticle[];
  warnings: string[];
  format: 'json' | 'markdown';
}

const ENGINE_SET = new Set<string>(ALL_ENGINES);

export function isEngineKind(v: string): v is EngineKind {
  return ENGINE_SET.has(v);
}

export function knowledgeTemplatePack(): KnowledgePack {
  return {
    kind: KNOWLEDGE_PACK_KIND,
    version: KNOWLEDGE_PACK_VERSION,
    articles: [
      {
        engine: 'hive',
        id: 'hive-team-naming',
        title: '团队建表约定',
        summary: '本租户 Hive 表命名与分区约定，导入后会出现在 Hive 手册里。',
        body: '把团队 Wiki / 规范写成手册。engine 只能是 hive、spark、clickhouse、doris。id 建议「引擎-主题」，同一租户下同 id 再导入会覆盖上一篇。',
        sourceUrl: 'https://wiki.example.com/hive-ddl',
        sourceLabel: '团队 Wiki',
        sections: [
          {
            heading: '命名',
            body: '明细表：dwd_{主题}_{过程}_{粒度}_{周期}。分区字段固定 dt，不要写进普通列。',
            sqlCaption: '外部表示例',
            sql: `CREATE EXTERNAL TABLE IF NOT EXISTS dwd_trd_order_di (
  order_id STRING COMMENT '订单ID',
  user_id STRING COMMENT '用户ID',
  pay_amt DECIMAL(18,2) COMMENT '实付金额'
)
COMMENT '订单明细'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/dw/dwd/trd/order_di';`,
            note: '删外部表默认不删 HDFS 文件。',
          },
        ],
        notes: ['导入只补充手册，不会改项目开通的引擎勾选。'],
      },
      {
        engine: 'spark',
        id: 'spark-team-overwrite',
        title: '按日重跑',
        summary: 'Spark 动态分区覆盖，避免 INSERT OVERWRITE 清掉其他天。',
        body: '重跑某一天时打开动态分区覆盖。',
        sections: [
          {
            heading: '写法',
            body: '分区列要出现在 SELECT 列表末尾。',
            sql: `SET spark.sql.sources.partitionOverwriteMode=dynamic;
INSERT OVERWRITE TABLE dwd_trd_order_di PARTITION (dt)
SELECT order_id, user_id, pay_amt, dt FROM stg_trd_order;`,
          },
        ],
      },
    ],
  };
}

export function knowledgeTemplateMarkdown() {
  return `---
engine: hive
id: hive-team-naming
title: 团队建表约定
summary: 本租户 Hive 表命名与分区约定
sourceUrl: https://wiki.example.com/hive-ddl
sourceLabel: 团队 Wiki
---

把团队 Wiki / 规范写成手册。一篇对应一个 \`---\` 头。engine 只能是 hive、spark、clickhouse、doris。

## 命名

明细表：dwd_{主题}_{过程}_{粒度}_{周期}。分区字段固定 dt。

\`\`\`sql
CREATE EXTERNAL TABLE IF NOT EXISTS dwd_trd_order_di (
  order_id STRING COMMENT '订单ID',
  user_id STRING COMMENT '用户ID',
  pay_amt DECIMAL(18,2) COMMENT '实付金额'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/dw/dwd/trd/order_di';
\`\`\`

> 删外部表默认不删 HDFS 文件。

---
engine: spark
id: spark-team-overwrite
title: 按日重跑
summary: Spark 动态分区覆盖
---

重跑某一天时打开动态分区覆盖。

## 写法

分区列要出现在 SELECT 列表末尾。

\`\`\`sql
SET spark.sql.sources.partitionOverwriteMode=dynamic;
INSERT OVERWRITE TABLE dwd_trd_order_di PARTITION (dt)
SELECT order_id, user_id, pay_amt, dt FROM stg_trd_order;
\`\`\`
`;
}

export function downloadKnowledgeTemplate(kind: 'json' | 'markdown') {
  if (kind === 'json') {
    downloadBlob(
      'dw-ai-knowledge-template.json',
      `${JSON.stringify(knowledgeTemplatePack(), null, 2)}\n`,
      'application/json;charset=utf-8'
    );
    return;
  }
  downloadBlob('dw-ai-knowledge-template.md', knowledgeTemplateMarkdown(), 'text/markdown;charset=utf-8');
}

function slug(input: string) {
  const s = input
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40);
  return s || 'article';
}

function asText(v: unknown) {
  return typeof v === 'string' ? v.trim() : '';
}

function parseSections(raw: unknown): KnowledgeSection[] {
  if (!Array.isArray(raw)) return [];
  const out: KnowledgeSection[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const row = item as Record<string, unknown>;
    const heading = asText(row.heading);
    const body = asText(row.body);
    const sql = asText(row.sql);
    if (!heading && !body && !sql) continue;
    out.push({
      heading: heading || '小节',
      body,
      sql: sql || undefined,
      sqlCaption: asText(row.sqlCaption) || undefined,
      note: asText(row.note) || undefined,
    });
  }
  return out;
}

function normalizeArticle(raw: Record<string, unknown>, index: number, warnings: string[]): KnowledgePackArticle | null {
  const engineRaw = asText(raw.engine).toLowerCase();
  if (!isEngineKind(engineRaw)) {
    warnings.push(`第 ${index + 1} 篇 engine 无效（${raw.engine ?? '空'}），只接受 ${ALL_ENGINES.join(' / ')}`);
    return null;
  }
  const title = asText(raw.title);
  if (!title) {
    warnings.push(`第 ${index + 1} 篇缺少 title，已跳过`);
    return null;
  }
  const sections = parseSections(raw.sections);
  const sql = asText(raw.sql);
  if (sql && !sections.length) {
    sections.push({
      heading: asText(raw.sqlCaption) || 'SQL',
      body: '',
      sql,
      sqlCaption: asText(raw.sqlCaption) || undefined,
    });
  }
  const body = asText(raw.body);
  if (!body && !sections.length) {
    warnings.push(`「${title}」没有 body / sections / sql，已跳过`);
    return null;
  }
  const id = asText(raw.id) || `${engineRaw}-${slug(title)}`;
  return {
    engine: engineRaw,
    id,
    title,
    summary: asText(raw.summary) || title,
    body,
    sourceUrl: asText(raw.sourceUrl) || undefined,
    sourceLabel: asText(raw.sourceLabel) || undefined,
    sections,
    notes: Array.isArray(raw.notes) ? raw.notes.map((n) => asText(n)).filter(Boolean) : undefined,
  };
}

function parseJsonPack(text: string): KnowledgeParseResult {
  const warnings: string[] = [];
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error('JSON 无法解析，请对照模版检查逗号和引号');
  }
  if (!data || typeof data !== 'object') throw new Error('JSON 根节点必须是对象');
  const root = data as Record<string, unknown>;
  if (root.kind != null && root.kind !== KNOWLEDGE_PACK_KIND) {
    warnings.push(`kind 应为 ${KNOWLEDGE_PACK_KIND}，当前是 ${String(root.kind)}，仍尝试读取`);
  }
  let list: unknown[] = [];
  if (Array.isArray(root.articles)) list = root.articles;
  else if (Array.isArray(root.engines)) {
    for (const eng of root.engines) {
      if (!eng || typeof eng !== 'object') continue;
      const pack = eng as Record<string, unknown>;
      const engine = asText(pack.engine);
      const arts = Array.isArray(pack.articles) ? pack.articles : [];
      for (const a of arts) {
        if (a && typeof a === 'object') list.push({ engine, ...(a as object) });
      }
    }
  } else {
    throw new Error('需要 articles 数组，或 engines[].articles。请下载模版');
  }
  const articles: KnowledgePackArticle[] = [];
  list.forEach((item, i) => {
    if (!item || typeof item !== 'object') {
      warnings.push(`第 ${i + 1} 篇不是对象，已跳过`);
      return;
    }
    const row = normalizeArticle(item as Record<string, unknown>, i, warnings);
    if (row) articles.push(row);
  });
  if (!articles.length) throw new Error(warnings[0] || '没有可导入的篇');
  return { articles, warnings, format: 'json' };
}

function parseFrontMatter(block: string) {
  const meta: Record<string, string> = {};
  const lines = block.replace(/^\uFEFF/, '').split(/\r?\n/);
  let i = 0;
  if (lines[0]?.trim() === '---') i = 1;
  for (; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim() === '---') {
      return { meta, body: lines.slice(i + 1).join('\n') };
    }
    const m = line.match(/^([A-Za-z][\w]*)\s*:\s*(.*)$/);
    if (m) meta[m[1]] = m[2].trim();
  }
  return { meta, body: block };
}

function splitMarkdownDocs(text: string) {
  const src = text.replace(/^\uFEFF/, '').trim();
  if (!src) return [];
  if (!src.startsWith('---')) return [src];
  const parts: string[] = [];
  const chunks = src.split(/\n(?=---\s*\n)/);
  for (const c of chunks) {
    const t = c.trim();
    if (t) parts.push(t);
  }
  return parts;
}

function parseMarkdownSections(body: string) {
  const intro: string[] = [];
  const sections: KnowledgeSection[] = [];
  const notes: string[] = [];
  const chunks = body.split(/\n(?=##\s+)/);
  for (const chunk of chunks) {
    const lines = chunk.replace(/\s+$/, '').split(/\r?\n/);
    const head = lines[0]?.match(/^##\s+(.+)$/);
    if (!head) {
      intro.push(chunk.trim());
      continue;
    }
    const rest = lines.slice(1).join('\n');
    const sqlMatch = rest.match(/```(?:sql)?\s*\n([\s\S]*?)```/i);
    const noteMatch = rest.match(/^>\s?(.+)$/m);
    const withoutSql = rest.replace(/```(?:sql)?\s*\n[\s\S]*?```/i, '').replace(/^>\s?.+$/m, '');
    sections.push({
      heading: head[1].trim(),
      body: withoutSql.replace(/\n{3,}/g, '\n\n').trim(),
      sql: sqlMatch?.[1].replace(/\s+$/, '') || undefined,
      note: noteMatch?.[1].trim(),
    });
  }
  const lead = intro.join('\n\n').trim();
  const leadNote = lead.match(/^>\s?(.+)$/m);
  if (leadNote) notes.push(leadNote[1].trim());
  return {
    body: lead.replace(/^>\s?.+$/m, '').trim(),
    sections,
    notes,
  };
}

function parseMarkdownPack(text: string): KnowledgeParseResult {
  const warnings: string[] = [];
  const articles: KnowledgePackArticle[] = [];
  splitMarkdownDocs(text).forEach((doc, i) => {
    const { meta, body } = parseFrontMatter(doc);
    const parsed = parseMarkdownSections(body);
    const row = normalizeArticle(
      {
        engine: meta.engine,
        id: meta.id,
        title: meta.title,
        summary: meta.summary,
        body: parsed.body,
        sourceUrl: meta.sourceUrl,
        sourceLabel: meta.sourceLabel,
        sections: parsed.sections,
        notes: parsed.notes.length ? parsed.notes : undefined,
      },
      i,
      warnings
    );
    if (row) articles.push(row);
  });
  if (!articles.length) throw new Error(warnings[0] || 'Markdown 里没有可导入的篇，请从 --- 头开始');
  return { articles, warnings, format: 'markdown' };
}

export function parseKnowledgeFile(filename: string, text: string): KnowledgeParseResult {
  const name = filename.toLowerCase();
  const trimmed = text.trim();
  if (name.endsWith('.json') || trimmed.startsWith('{')) return parseJsonPack(trimmed);
  if (name.endsWith('.md') || name.endsWith('.markdown') || trimmed.startsWith('---')) {
    return parseMarkdownPack(text);
  }
  if (trimmed.startsWith('{')) return parseJsonPack(trimmed);
  return parseMarkdownPack(text);
}

export function toTenantArticles(
  articles: KnowledgePackArticle[],
  tenantId: string,
  importedBy: string,
  importedAt: string
): TenantKnowledgeArticle[] {
  return articles.map((a) => ({
    id: a.id || `${a.engine}-${slug(a.title)}`,
    tenantId,
    engine: a.engine,
    title: a.title,
    summary: a.summary || a.title,
    body: a.body || '',
    sourceUrl: a.sourceUrl,
    sourceLabel: a.sourceLabel || undefined,
    sections: a.sections ?? [],
    notes: a.notes,
    importedAt,
    importedBy,
  }));
}

export function knowledgeFormatHint() {
  return [
    { key: 'kind', text: `JSON 根节点 kind 固定为 ${KNOWLEDGE_PACK_KIND}，version 为 ${KNOWLEDGE_PACK_VERSION}` },
    { key: 'engine', text: `每篇必填 engine：${ALL_ENGINES.map(engineLabel).join(' / ')}` },
    { key: 'title', text: '每篇必填 title；id 可选，默认按「引擎-标题」生成' },
    { key: 'body', text: 'body 为导语；sections[] 为小节，可含 heading / body / sql / sqlCaption / note' },
    { key: 'md', text: 'Markdown：每篇以 --- 开头写 engine / title 等字段，正文用 ## 小节，```sql 代码块，> 注意' },
  ];
}
