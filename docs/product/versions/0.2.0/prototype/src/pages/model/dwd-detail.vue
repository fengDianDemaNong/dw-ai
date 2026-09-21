<template>
  <div class="page" v-if="table">
    <PageHeader :title="table.name" :subtitle="table.comment || `${layer} 表明细`">
      <template #actions>
        <a-button @click="back">返回列表</a-button>
        <a-button @click="goVersions">版本</a-button>
        <a-tooltip :title="hasAiCap('model_design') ? '' : '本组织未开通此项'">
          <a-button :disabled="!hasAiCap('model_design')" @click="goAi">AI 改表</a-button>
        </a-tooltip>
        <a-button @click="editing = true">编辑</a-button>
        <a-button
          v-if="canWriteModel && table.status === 'draft'"
          type="primary"
          @click="openPublish"
        >发布</a-button>
        <a-button danger @click="askRemove">删除</a-button>
      </template>
    </PageHeader>

    <div class="stat-grid">
      <div class="stat">
        <div class="k">主题域</div>
        <div class="v" style="font-size: 18px">{{ table.domain || '未归属' }}</div>
      </div>
      <div class="stat">
        <div class="k">粒度</div>
        <div class="v" style="font-size: 18px">{{ table.grain || '—' }}</div>
      </div>
      <div class="stat">
        <div class="k">周期 / 分区</div>
        <div class="v" style="font-size: 18px">{{ table.period || '—' }} / {{ table.partition || '—' }}</div>
      </div>
      <div class="stat">
        <div class="k">数据等级</div>
        <div class="v" style="font-size: 16px"><GradeTag :code="table.grade" policy /></div>
      </div>
      <div class="stat">
        <div class="k">状态</div>
        <div class="v" style="font-size: 18px">{{ statusLabel }}</div>
      </div>
      <div class="stat">
        <div class="k">版本</div>
        <div class="v" style="font-size: 18px">v{{ currentTableVersion(table.id) || 1 }}</div>
      </div>
    </div>

    <div class="card mb">
      <h3>来源</h3>
      <p v-if="!upstreams.length" class="muted">无来源表</p>
      <div v-for="s in sourceRows" :key="s.alias" class="src">
        <code>{{ s.alias }}</code>
        <a v-if="s.table" @click="goTable(s.table)">{{ s.table.name }}</a>
        <span v-else>{{ s.tableId }}</span>
      </div>
      <div v-if="table.joins?.length" class="muted mt">
        关联：
        <span v-for="(j, i) in table.joins" :key="i">
          {{ j.type === 'left' ? 'LEFT' : 'INNER' }} {{ j.leftAlias }}.{{ j.leftColumn }} = {{ j.rightAlias }}.{{ j.rightColumn }}
          <span v-if="i < table.joins.length - 1">；</span>
        </span>
      </div>
    </div>

    <div class="card mb">
      <h3>字段</h3>
      <a-table :data-source="table.columns" :columns="colCols" size="small" :pagination="false" row-key="name">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <a @click="picked = record">{{ record.name }}</a>
          </template>
          <template v-else-if="column.key === 'null'">
            {{ record.nullable === false ? '否' : '是' }}
          </template>
          <template v-else-if="column.key === 'grade'">
            <GradeTag :code="record.grade || table.grade" />
          </template>
          <template v-else-if="column.key === 'sens'">
            <a-tag v-if="record.sensitive" color="red">敏感</a-tag>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'logic'">
            <span :class="{ muted: logicSummary(record) === '尚未定义' }">{{ logicSummary(record) }}</span>
          </template>
        </template>
      </a-table>
    </div>

    <div class="card">
      <div class="pane-bar">
        <a-radio-group v-model:value="bottomPane" size="small" button-style="solid">
          <a-radio-button value="sql">逻辑 SQL</a-radio-button>
          <a-radio-button value="lineage">血缘</a-radio-button>
          <a-radio-button value="impact">下游影响</a-radio-button>
        </a-radio-group>
      </div>
      <DdlPreview v-if="bottomPane === 'sql'" :spec="ddlSpec" :dml-sql="etlSql" title="" />
      <template v-else-if="bottomPane === 'lineage'">
        <p class="muted pane-hint">设计态：边来自来源声明和字段加工。作业态在独立元数据产品里，两张图不互相覆盖。</p>
        <p class="pane-hint">
          <a-button size="small" type="link" @click="goJobLineage">查看作业血缘</a-button>
        </p>
        <TableLineage :table="table" :catalog="projectTables" @open="goTable" />
      </template>
      <template v-else>
        <p v-if="table.status === 'published'" class="muted pane-hint">当前已发布。改表保存为草稿后，这里列出相对上一版受影响的下游表字段。</p>
        <template v-else>
          <p class="muted pane-hint">相对上一发布版，本次草稿可能影响到：</p>
          <p v-if="!impactLines.length" class="muted">无下游表字段受影响。</p>
          <ul v-else class="hits">
            <li v-for="(line, i) in impactLines" :key="i">{{ line }}</li>
          </ul>
        </template>
      </template>
    </div>

    <TableFormModal :open="editing" :table="table" :layer="layer" @close="editing = false" />
    <a-modal v-model:open="publishing" title="发布并生成新版本" ok-text="发布" @ok="doPublish">
      <p class="muted">发布后状态为已发布，并记一版。不自动改下游。</p>
      <a-form-item label="备注">
        <a-textarea v-model:value="publishNote" :rows="2" placeholder="如：增加支付用户数口径" />
      </a-form-item>
      <h4>影响的下游表字段</h4>
      <p v-if="!impactLines.length" class="muted">无下游表字段受影响。</p>
      <ul v-else class="hits">
        <li v-for="(line, i) in impactLines" :key="i">{{ line }}</li>
      </ul>
    </a-modal>
    <a-drawer :open="Boolean(picked)" :title="picked?.name" width="480" @close="picked = null">
      <template v-if="picked">
        <p><b>口径</b> {{ picked.logic?.desc || '尚未定义' }}</p>
        <p>
          <b>类型</b>
          <router-link v-if="picked.logic?.kind" :to="`/app/spec/logic#${picked.logic.kind}`">{{ kindLabel(picked.logic.kind) }}</router-link>
          <span v-else>—</span>
        </p>
        <p><b>表达式</b> <code>{{ renderFieldExpr(picked) || '—' }}</code></p>
        <p v-if="picked.logic?.filter"><b>过滤</b> {{ picked.logic.filter }}</p>
        <h4>上游列</h4>
        <p v-if="!picked.logic?.sources?.length" class="muted">无</p>
        <div v-for="(s, i) in picked.logic?.sources ?? []" :key="i">{{ s.alias }}.{{ s.column }}</div>
        <h4>下游引用</h4>
        <p v-if="!fieldDown.length" class="muted">无</p>
        <div v-for="(h, i) in fieldDown" :key="i">{{ h.tableName }}.{{ h.field || '*' }} · {{ h.note }}</div>
        <div class="sql-h">
          <h4>查询 SQL</h4>
          <a v-if="fieldSql" @click="copyFieldSql">复制</a>
        </div>
        <SqlBlock v-if="fieldSql" :text="fieldSql" />
        <p v-else class="muted">
          尚未定义加工，无法生成 SQL
          <a @click="goEditFromDrawer">去编辑</a>
        </p>
      </template>
    </a-drawer>
  </div>
  <div v-else class="page">
    <p class="muted">表不存在或已删除。</p>
    <a-button @click="back">返回</a-button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message, Modal } from 'ant-design-vue';
import {
  fieldDependents,
  LOGIC_KIND_LABEL,
  logicSummary,
  renderEtlSql,
  renderFieldExpr,
  renderFieldQuerySql,
  resolvedSources,
  specFromTable,
  tableDependentsOf,
} from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';
import DdlPreview from '../../components/DdlPreview.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import TableFormModal from '../../components/TableFormModal.vue';
import TableLineage from '../../components/TableLineage.vue';
import GradeTag from '../../components/GradeTag.vue';
import { layerAiHref, layerHref, layerVersionsHref, parseLayerParam } from '../../config/layers';
import {
  canWriteModel,
  currentTableVersion,
  hasAiCap,
  impactOfWorkingCopy,
  projectGrades,
  projectTables,
  publishTable,
  removeTable,
} from '../../stores/app';
import type { Column, FieldLogicKind, WarehouseTable } from '../../types';

const route = useRoute();
const router = useRouter();
const editing = ref(false);
const publishing = ref(false);
const publishNote = ref('');
const bottomPane = ref<'sql' | 'lineage' | 'impact'>('sql');
const picked = ref<Column | null>(null);
const layer = computed(() => parseLayerParam(route.params.layer));
const domain = computed(() => String(route.params.domain ?? '_none'));
const table = computed(() => projectTables.value.find((t) => t.id === route.params.tableId));

const sourceRows = computed(() =>
  resolvedSources(table.value ?? { sources: [], createdFrom: undefined }).map((s) => ({
    ...s,
    table: projectTables.value.find((x) => x.id === s.tableId),
  }))
);
const upstreams = computed(() => sourceRows.value.map((s) => s.table).filter((x): x is WarehouseTable => Boolean(x)));
const downstreams = computed(() =>
  table.value ? tableDependentsOf(table.value.id, projectTables.value) : []
);
const fieldDown = computed(() =>
  table.value && picked.value ? fieldDependents(table.value.id, picked.value.name, projectTables.value) : []
);
const etlSql = computed(() => (table.value ? renderEtlSql(table.value, projectTables.value) : ''));
const fieldSql = computed(() =>
  table.value && picked.value ? renderFieldQuerySql(table.value, picked.value, projectTables.value) : ''
);
const statusLabel = computed(() => {
  const s = table.value?.status;
  if (s === 'published') return '已发布';
  if (s === 'deprecated') return '已下线';
  return '草稿';
});
const impactLines = computed(() => {
  if (!table.value) return [];
  const r = impactOfWorkingCopy(table.value.id);
  if (!r?.hits.length) return [];
  return r.lines;
});

const colCols = [
  { title: '字段', key: 'name', width: 160 },
  { title: '类型', dataIndex: 'type', width: 120 },
  { title: '注释', dataIndex: 'comment' },
  { title: '加工 / 口径', key: 'logic' },
  { title: '等级', key: 'grade', width: 120 },
  { title: '可空', key: 'null', width: 60 },
  { title: '敏感', key: 'sens', width: 70 },
];

const ddlSpec = computed(() =>
  table.value ? specFromTable(table.value, { grades: projectGrades.value }) : null
);

function kindLabel(k: FieldLogicKind) {
  return LOGIC_KIND_LABEL[k];
}
function goEditFromDrawer() {
  picked.value = null;
  editing.value = true;
}
async function copyFieldSql() {
  if (!fieldSql.value) return;
  try {
    await navigator.clipboard.writeText(fieldSql.value);
    message.success('已复制');
  } catch {
    message.warning('复制失败，请手动选择');
  }
}
function goTable(t: WarehouseTable) {
  router.push(`${layerHref(t.layer)}/${encodeURIComponent(t.domain || '_none')}/${t.id}`);
}
function goJobLineage() {
  if (!table.value) return;
  const full = `hive_prod.${table.value.layer.toLowerCase()}.${table.value.name}`;
  router.push({ path: '/app/map/tables', query: { start: full } });
}
function back() {
  router.push(`${layerHref(layer.value)}/${domain.value}`);
}
function goVersions() {
  if (!table.value) return;
  router.push(layerVersionsHref(layer.value, domain.value, table.value.id));
}
function goAi() {
  if (!table.value) return;
  router.push(layerAiHref(layer.value, table.value.id));
}
function openPublish() {
  publishNote.value = '';
  publishing.value = true;
}
function doPublish() {
  if (!table.value) return;
  if (publishTable(table.value.id, publishNote.value)) publishing.value = false;
}
function askRemove() {
  if (!table.value) return;
  const names = downstreams.value.map((d) => d.name);
  Modal.confirm({
    title: names.length ? `确定删除？下游：${names.join('、')}` : '确定删除该表？',
    okText: '删除本表',
    okType: 'danger',
    onOk: () => {
      removeTable(table.value!.id);
      back();
    },
  });
}
</script>

<style scoped>
h3, h4 { margin: 0 0 10px; font-size: 14px; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 8px; }
.src { display: flex; gap: 8px; margin-bottom: 4px; }
.hits { margin: 0; padding-left: 18px; font-size: 13px; }
.sql-h { display: flex; align-items: center; justify-content: space-between; margin: 12px 0 6px; }
.pane-bar { margin-bottom: 10px; }
.pane-hint { margin: 0 0 10px; font-size: 12px; }
</style>
