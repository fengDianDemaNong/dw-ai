<template>
  <div class="page">
    <PageHeader title="DWD → DWS 智能汇总" subtitle="面向维度+度量组合的星型模型。生成时套用本层命名、脱敏与空值规范，规范错误禁止发布。">
      <template #actions>
        <a-button @click="router.push(layerHref('DWS'))">查看 DWS 总览</a-button>
        <a-button v-if="canWrite" type="primary" :disabled="!sourceId" @click="run">AI 设计汇总表</a-button>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <span>源 DWD</span>
      <a-select v-model:value="sourceId" style="width: 320px" :options="sourceOptions" />
      <span>维度</span>
      <a-select
        v-model:value="dims"
        mode="multiple"
        style="width: 420px"
        :options="dimOptions"
        placeholder="从 DWD 中选择高频维度"
      />
    </div>

    <div v-if="policy" class="card mb">
      本层规范：脱敏 {{ maskingLabel(policy.masking) }} · 空值 {{ nullLabel(policy.nullHandling) }}
      · 对外 {{ policy.serve === 'forbid' ? '禁止' : policy.serve === 'approval' ? '需审批' : '允许' }}
    </div>

    <div v-if="draft" class="grid">
      <div class="card">
        <DdlPreview :spec="ddlSpec" title="生成 DDL" />
      </div>
      <div class="card">
        <h3>预聚合 SQL</h3>
        <SqlBlock :text="draft.etlSql" />
        <div class="card inner">
          <h3>规范校验</h3>
          <div v-for="(iss, i) in draft.specIssues" :key="i">
            <a-tag :color="iss.level === 'error' ? 'red' : iss.level === 'warn' ? 'orange' : 'green'">{{ iss.level }}</a-tag>
            {{ iss.message }}
          </div>
          <div v-if="!draft.specIssues.length" class="muted">无规范问题</div>
        </div>
        <div class="card inner">
          <h3>人工审核点</h3>
          <a-checkbox-group v-model:value="checks">
            <div><a-checkbox value="grain">汇总粒度合适，不是一张大宽表</a-checkbox></div>
            <div><a-checkbox value="dims">维度不含需禁止下沉的 PII</a-checkbox></div>
            <div><a-checkbox value="naming">命名与本层规范一致</a-checkbox></div>
          </a-checkbox-group>
        </div>
        <div class="btns" v-if="draft.status === 'pending_review' && (canWrite || canPublishPerm)">
          <a-button v-if="canWrite" @click="rejectDraft(draft.id)">打回</a-button>
          <a-button v-if="canPublishPerm" type="primary" :disabled="!canPublish" @click="approveDraft(draft.id)">审核通过并发布 DWS</a-button>
        </div>
        <a-tag v-else :color="draft.status === 'approved' ? 'green' : 'default'">{{ draft.status }}</a-tag>
      </div>
    </div>

    <div v-if="draft" class="card mt">
      <h3>建议质量规则</h3>
      <p class="muted hint">建模中心只保存建议，不在本页执行巡检。</p>
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
import { hydrateLayerRule, maskingLabel, nullLabel } from '../../config/layerPolicies';
import { layerHref } from '../../config/layers';
import { approveDraft, can, projectDrafts, projectLayerRules, projectTables, rejectDraft, runDwdToDws } from '../../stores/app';

const router = useRouter();
const canWrite = computed(() => can('model:write'));
const canPublishPerm = computed(() => can('model:publish'));

const dwd = computed(() => {
  const list = projectTables.value.filter((t) => t.layer === 'DWD');
  return [
    ...list.filter((t) => t.status !== 'deprecated'),
    ...list.filter((t) => t.status === 'deprecated'),
  ];
});
const sourceOptions = computed(() =>
  dwd.value.map((t) => ({
    value: t.id,
    label: t.status === 'deprecated' ? `${t.name}（已下线）` : t.name,
  }))
);
const sourceId = ref(dwd.value.find((t) => t.status !== 'deprecated')?.id);
const source = computed(() => projectTables.value.find((t) => t.id === sourceId.value));
const dimOptions = computed(() =>
  (source.value?.columns ?? [])
    .filter((c) => !/amt|cnt|qty|gmv/.test(c.name))
    .map((c) => ({ value: c.name, label: `${c.name} ${c.comment}` }))
);
const dims = ref<string[]>(
  (source.value?.columns ?? [])
    .filter((c) => ['dt', 'user_type', 'item_category'].includes(c.name))
    .map((c) => c.name)
);
const draftId = ref<string>();
const checks = ref<string[]>([]);
const draft = computed(
  () => projectDrafts.value.find((d) => d.id === draftId.value) ?? projectDrafts.value.find((d) => d.targetLayer === 'DWS')
);
const policy = computed(() => {
  const r = projectLayerRules.value.find((x) => x.layer === 'DWS');
  return r ? hydrateLayerRule(r) : undefined;
});
const canPublish = computed(() => {
  if (checks.value.length < 3) return false;
  return !(draft.value?.specIssues ?? []).some((i) => i.level === 'error');
});
const ddlSpec = computed(() => (draft.value ? specFromModelingDraft(draft.value) : null));
const qCols = [
  { title: '类型', dataIndex: 'type', width: 120 },
  { title: '字段', dataIndex: 'field', width: 160 },
  { title: '逻辑', dataIndex: 'logic' },
  { title: '阈值', dataIndex: 'threshold', width: 140 },
];

function run() {
  if (!sourceId.value) return;
  const d = runDwdToDws(sourceId.value, dims.value);
  draftId.value = d?.id;
  checks.value = [];
}
</script>

<style scoped>
h3 { margin: 0 0 10px; font-size: 14px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.btns { display: flex; gap: 8px; margin-top: 12px; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.hint { margin: 0 0 8px; }
.inner { margin-top: 12px; padding: 10px 12px; background: #f8fafc; border: 1px dashed var(--line); }
.muted { color: #64748b; font-size: 12px; }
</style>
