<template>
  <div class="page">
    <PageHeader title="从 ODS 生成 DWD" subtitle="输入 ODS 表结构 + 词根库，输出标准 DDL、ETL 与质量规则。置信度低于 0.8 的主题域必须人工确认。">
      <template #actions>
        <a-button @click="router.push(layerHref('DWD'))">查看 DWD 总览</a-button>
        <a-button type="primary" :disabled="!sourceId" :loading="running" @click="run">AI 生成草案</a-button>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <span>源 ODS 表</span>
      <a-select
        v-model:value="sourceId"
        style="width: 360px"
        :options="odsTables.map((t) => ({ value: t.id, label: `${t.name}  ${t.comment}` }))"
      />
    </div>

    <div v-if="source" class="card mb">
      <h3>源表字段</h3>
      <a-table :data-source="source.columns" :columns="srcCols" size="small" :pagination="false" row-key="name" />
    </div>

    <div v-if="draft" class="grid">
      <div class="card">
        <h3>步骤 1–2 · 字段识别与主题域</h3>
        <p>
          主题域
          <a-tag color="cyan">{{ draft.domainCode }}</a-tag>
          置信度
          <b :class="draft.domainConfidence < 0.8 ? 'warn' : ''">{{ draft.domainConfidence }}</b>
          · 粒度 <a-tag>{{ draft.grain }}</a-tag>
          · 主键 <code>{{ draft.primaryKeys.join(' + ') }}</code>
        </p>
        <a-table :data-source="draft.fieldTags" :columns="tagCols" size="small" :pagination="false" row-key="field">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'conf'">
              <a-progress :percent="Math.round(record.confidence * 100)" size="small" :status="record.confidence < 0.7 ? 'exception' : 'normal'" />
            </template>
            <template v-else-if="column.key === 'sens'">
              <a-tag v-if="record.dropped" color="red">剔除</a-tag>
              <a-tag v-else-if="record.transform === 'mask'" color="orange">掩码</a-tag>
              <a-tag v-else-if="record.transform === 'hash'" color="orange">哈希</a-tag>
              <a-tag v-else-if="record.transform === 'encrypt'" color="orange">加密</a-tag>
              <a-tag v-else-if="record.sensitive" color="red">敏感</a-tag>
            </template>
          </template>
        </a-table>
      </div>

      <div>
        <div class="card mb">
          <h3>规范校验</h3>
          <div v-for="(iss, i) in draft.specIssues" :key="i">
            <a-tag :color="iss.level === 'error' ? 'red' : iss.level === 'warn' ? 'orange' : 'green'">{{ iss.level }}</a-tag>
            {{ iss.message }}
          </div>
        </div>
        <div class="card mb">
          <h3>人工审核点</h3>
          <a-checkbox-group v-model:value="checks">
            <div><a-checkbox value="domain">主题域归属正确</a-checkbox></div>
            <div><a-checkbox value="grain">粒度合适（无需拆成多张 DWD）</a-checkbox></div>
            <div><a-checkbox value="sens">敏感字段已处理</a-checkbox></div>
          </a-checkbox-group>
        </div>
        <div class="btns" v-if="draft.status === 'pending_review'">
          <a-button danger @click="rejectDraft(draft.id)">打回</a-button>
          <a-button type="primary" :disabled="checks.length < 3 || hasSpecError" @click="approveDraft(draft.id)">审核通过并发布</a-button>
        </div>
        <a-tag v-else :color="draft.status === 'approved' ? 'green' : 'default'">{{ draft.status }}</a-tag>
      </div>
    </div>

    <div v-if="draft" class="grid2">
      <div class="card">
        <DdlPreview :spec="ddlSpec" title="生成 DDL" />
      </div>
      <div class="card">
        <h3>ETL 映射</h3>
        <SqlBlock :text="draft.etlSql" />
      </div>
    </div>

    <div v-if="draft" class="card mt">
      <h3>自动质量规则</h3>
      <a-table :data-source="draft.qualityRules" :columns="qCols" size="small" :pagination="false" row-key="id" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { specFromModelingDraft } from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';
import DdlPreview from '../../components/DdlPreview.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import { layerHref } from '../../config/layers';
import { approveDraft, projectDrafts, projectTables, rejectDraft, runOdsToDwd } from '../../stores/app';

const router = useRouter();

const odsTables = computed(() => projectTables.value.filter((t) => t.layer === 'ODS'));
const sourceId = ref(odsTables.value[0]?.id);
const source = computed(() => projectTables.value.find((t) => t.id === sourceId.value));
const running = ref(false);
const checks = ref<string[]>([]);
const draftId = ref<string>();
const draft = computed(() => projectDrafts.value.find((d) => d.id === draftId.value) ?? projectDrafts.value.find((d) => d.targetLayer === 'DWD'));
const hasSpecError = computed(() => (draft.value?.specIssues ?? []).some((i) => i.level === 'error'));
const ddlSpec = computed(() => (draft.value ? specFromModelingDraft(draft.value) : null));

const srcCols = [
  { title: '字段', dataIndex: 'name', width: 160 },
  { title: '类型', dataIndex: 'type', width: 140 },
  { title: '注释', dataIndex: 'comment' },
];
const tagCols = [
  { title: '源字段', dataIndex: 'field', width: 140 },
  { title: '建议名', dataIndex: 'suggestedName', width: 180 },
  { title: '语义', dataIndex: 'meaning' },
  { title: '词根', dataIndex: 'roots', width: 120 },
  { title: '置信度', key: 'conf', width: 140 },
  { title: '处理', key: 'sens', width: 80 },
];
const qCols = [
  { title: '类型', dataIndex: 'type', width: 120 },
  { title: '字段', dataIndex: 'field', width: 160 },
  { title: '逻辑', dataIndex: 'logic' },
  { title: '阈值', dataIndex: 'threshold', width: 140 },
];

async function run() {
  running.value = true;
  await new Promise((r) => setTimeout(r, 600));
  const d = runOdsToDwd(sourceId.value);
  draftId.value = d?.id;
  checks.value = [];
  running.value = false;
}
</script>

<style scoped>
h3 { margin: 0 0 10px; font-size: 14px; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.grid { display: grid; grid-template-columns: 1.4fr 0.8fr; gap: 12px; margin-bottom: 12px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.warn { color: #d97706; }
.btns { display: flex; gap: 8px; margin-top: 8px; }
</style>
