<template>
  <div class="page">
    <PageHeader
      title="知识库"
      subtitle="按引擎提供手册。内置 Hive / Spark / ClickHouse / Doris 操作说明；也可按模版导入本组织补充篇。"
    />

    <section class="card block">
      <h3>项目开通</h3>
      <p class="lead">同一组织下，不同项目可以挂不同引擎。未勾选的项目进知识库是空的。导入的篇会跟已开通引擎一起出现。</p>
      <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'engines'">
            <a-checkbox-group
              :value="record.engines"
              :options="engineOpts"
              :disabled="!isTenantAdmin"
              @change="(v: unknown) => save(record.id, v as EngineKind[])"
            />
          </template>
        </template>
      </a-table>
      <p v-if="!rows.length" class="muted">还没有项目。请先到项目管理新建。</p>
    </section>

    <section class="card block">
      <div class="imp-h">
        <div>
          <h3>导入手册</h3>
          <p class="lead">下载模版改完再上传。支持 JSON 与 Markdown。导入属于本组织，不改内置原文；同引擎同 id 会覆盖上一篇导入（或盖住同 id 内置篇的展示）。</p>
        </div>
        <a-space wrap>
          <a-button @click="downloadKnowledgeTemplate('json')">下载 JSON 模版</a-button>
          <a-button @click="downloadKnowledgeTemplate('markdown')">下载 Markdown 模版</a-button>
          <a-button type="primary" :disabled="!isTenantAdmin" @click="pick">上传导入</a-button>
        </a-space>
      </div>

      <div class="fmt">
        <div class="fmt-col">
          <b>JSON</b>
          <pre class="code">{{ jsonSample }}</pre>
        </div>
        <div class="fmt-col">
          <b>Markdown</b>
          <pre class="code">{{ mdSample }}</pre>
        </div>
      </div>
      <ul class="hint">
        <li v-for="h in knowledgeFormatHint()" :key="h.key">{{ h.text }}</li>
      </ul>

      <div v-if="preview" class="preview">
        <div class="imp-h">
          <div>
            <h3>导入预览</h3>
            <p class="muted">{{ fileName }} · {{ preview.format }} · {{ preview.articles.length }} 篇</p>
          </div>
          <a-space>
            <a-checkbox v-model:checked="replaceEngine">先清空这些引擎已导入的篇</a-checkbox>
            <a-button @click="preview = null">取消</a-button>
            <a-button type="primary" :loading="importing" @click="confirmImport">导入到本组织</a-button>
          </a-space>
        </div>
        <a-alert
          v-if="preview.warnings.length"
          type="warning"
          show-icon
          class="mb"
          :message="preview.warnings.join('；')"
        />
        <a-table
          size="small"
          row-key="rowKey"
          :pagination="false"
          :data-source="previewRows"
          :columns="previewCols"
        />
      </div>

      <div v-if="importedRows.length" class="imported">
        <h3>已导入 {{ importedRows.length }} 篇</h3>
        <a-table
          size="small"
          row-key="rowKey"
          :pagination="false"
          :data-source="importedRows"
          :columns="importedCols"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'act'">
              <a-button type="link" size="small" danger :disabled="!isTenantAdmin" @click="removeImportedArticle(record.id, record.engine)">
                删除
              </a-button>
            </template>
          </template>
        </a-table>
      </div>
    </section>

    <section class="block">
      <h3 class="sec">手册</h3>
      <KnowledgeManual :engines="ALL_ENGINES" :manage="isTenantAdmin" />
    </section>

    <input ref="fileRef" type="file" class="hidden" accept=".json,.md,.markdown,application/json,text/markdown" @change="onFile" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import KnowledgeManual from '../../components/KnowledgeManual.vue';
import { ALL_ENGINES, ENGINE_OPTIONS, engineLabel } from '../../config/knowledge';
import {
  downloadKnowledgeTemplate,
  knowledgeFormatHint,
  parseKnowledgeFile,
  type KnowledgeParseResult,
} from '../../engine/knowledgeIo';
import type { EngineKind } from '../../types';
import {
  app,
  importKnowledgeFile,
  isTenantAdmin,
  loadTenantKnowledge,
  removeImportedArticle,
  setProjectEngines,
  tenantKnowledgeArticles,
} from '../../stores/app';

const engineOpts = ENGINE_OPTIONS.map((e) => ({ value: e.value, label: e.label }));
const fileRef = ref<HTMLInputElement | null>(null);
const fileName = ref('');
const rawText = ref('');
const preview = ref<KnowledgeParseResult | null>(null);
const replaceEngine = ref(false);
const importing = ref(false);

const jsonSample = `{
  "kind": "dw-ai.knowledge",
  "version": 1,
  "articles": [
    {
      "engine": "hive",
      "id": "hive-team-naming",
      "title": "团队建表约定",
      "summary": "一句话，出现在左侧目录",
      "body": "导语",
      "sections": [
        { "heading": "命名", "body": "说明", "sql": "CREATE TABLE ...", "note": "注意" }
      ]
    }
  ]
}`;

const mdSample = `---
engine: hive
id: hive-team-naming
title: 团队建表约定
summary: 一句话
---

导语

## 命名

说明

\`\`\`sql
CREATE TABLE ...
\`\`\`

> 注意`;

const rows = computed(() =>
  app.projects
    .filter((p) => p.tenantId === app.currentTenantId)
    .map((p) => ({
      id: p.id,
      name: p.name,
      code: p.code,
      engines: [...(p.engines ?? [])],
    }))
);

const cols = [
  { title: '项目', dataIndex: 'name', width: 180 },
  { title: '编码', dataIndex: 'code', width: 140 },
  { title: '引擎知识库', key: 'engines' },
];

const previewRows = computed(() =>
  (preview.value?.articles ?? []).map((a) => ({
    ...a,
    rowKey: `${a.engine}:${a.id}`,
    engineLabel: engineLabel(a.engine),
    sectionCount: a.sections?.length ?? 0,
  }))
);

const previewCols = [
  { title: '引擎', dataIndex: 'engineLabel', width: 110 },
  { title: 'id', dataIndex: 'id', width: 180 },
  { title: '标题', dataIndex: 'title' },
  { title: '小节', dataIndex: 'sectionCount', width: 70 },
];

const importedRows = computed(() =>
  tenantKnowledgeArticles.value.map((a) => ({
    ...a,
    rowKey: `${a.engine}:${a.id}`,
    engineLabel: engineLabel(a.engine),
  }))
);

const importedCols = [
  { title: '引擎', dataIndex: 'engineLabel', width: 110 },
  { title: 'id', dataIndex: 'id', width: 180 },
  { title: '标题', dataIndex: 'title' },
  { title: '导入时间', dataIndex: 'importedAt', width: 160 },
  { title: '', key: 'act', width: 80 },
];

function save(projectId: string, engines: EngineKind[]) {
  void setProjectEngines(projectId, engines).catch((e) => {
    message.error(e instanceof Error ? e.message : '保存失败');
  });
}

function pick() {
  if (!isTenantAdmin.value) {
    message.error('需要组织管理员权限');
    return;
  }
  fileRef.value?.click();
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  fileName.value = file.name;
  try {
    rawText.value = await file.text();
    preview.value = parseKnowledgeFile(file.name, rawText.value);
    replaceEngine.value = false;
  } catch (err) {
    preview.value = null;
    message.error(err instanceof Error ? err.message : '文件无法识别');
  }
}

async function confirmImport() {
  if (!preview.value || !rawText.value) return;
  importing.value = true;
  try {
    await importKnowledgeFile(rawText.value, fileName.value, replaceEngine.value ? 'replace-engine' : 'merge');
    preview.value = null;
    rawText.value = '';
  } catch (e) {
    message.error(e instanceof Error ? e.message : '导入失败');
  } finally {
    importing.value = false;
  }
}

onMounted(() => {
  void loadTenantKnowledge();
});
</script>

<style scoped>
.block {
  margin-bottom: 20px;
}

.sec {
  margin: 0 0 10px;
  font-size: 15px;
}

.lead,
.muted {
  color: var(--muted);
  font-size: 13px;
  margin: 0 0 12px;
}

h3 {
  margin: 0 0 8px;
  font-size: 15px;
}

.imp-h {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.fmt {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 12px 0;
}

.fmt-col b {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
}

.code {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #f8fafc;
  font-size: 11px;
  line-height: 1.5;
  overflow: auto;
  max-height: 240px;
  white-space: pre;
}

.hint {
  margin: 0 0 8px;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.preview,
.imported {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.mb {
  margin-bottom: 10px;
}

.hidden {
  display: none;
}

@media (max-width: 900px) {
  .fmt {
    grid-template-columns: 1fr;
  }
}
</style>
