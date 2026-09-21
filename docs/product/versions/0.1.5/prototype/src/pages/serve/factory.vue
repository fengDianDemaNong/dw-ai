<template>
  <div class="page">
    <PageHeader title="指标工厂" subtitle="公共空间按业务分类浏览公司口径；业务空间由部门建目录；个人空间仅自己可见，在此建指标，再发布到业务或公共（公共需审核）。">
      <template #actions>
        <a-button v-if="space !== 'public'" @click="folderOpen = true">新建目录</a-button>
        <a-button v-if="space === 'personal'" type="primary" :disabled="!folderId" @click="createOpen = true">新建指标</a-button>
      </template>
    </PageHeader>

    <a-radio-group v-model:value="space" class="mb" button-style="solid" @change="onSpace">
      <a-radio-button value="public">公共空间</a-radio-button>
      <a-radio-button value="business">业务空间</a-radio-button>
      <a-radio-button value="personal">个人空间</a-radio-button>
    </a-radio-group>

    <div class="split">
      <aside class="tree card">
        <div class="tree-h">{{ spaceLabel }}目录</div>
        <button
          v-for="f in folders"
          :key="f.id"
          type="button"
          class="node"
          :class="{ on: folderId === f.id }"
          @click="folderId = f.id"
        >
          {{ f.name }}
          <small>{{ countIn(f.id) }}</small>
        </button>
        <p v-if="!folders.length" class="hint">还没有目录。{{ space === 'personal' ? '先建一个个人分类。' : '' }}</p>
      </aside>

      <section class="card card-flush grow">
        <a-table :data-source="rows" :columns="cols" size="small" :pagination="false" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a v-if="space !== 'personal'" @click="copyOne(record.id)">复制到个人</a>
              <template v-else>
                <a @click="publishOne(record.id, 'business')">发到业务</a>
                <a class="gap" @click="publishOne(record.id, 'public')">申请公共</a>
              </template>
              <template v-if="record.scope === 'public' && record.status === 'review'">
                <a class="gap" @click="approveServeMetric(record.id)">通过</a>
                <a class="gap" @click="rejectServeMetric(record.id)">打回</a>
              </template>
            </template>
          </template>
        </a-table>
        <p v-if="space === 'public'" class="hint pad">待审条目只在工厂公共空间可见，市场货架只展示已通过的公共指标。</p>
      </section>
    </div>

    <a-modal v-model:open="folderOpen" title="新建目录" ok-text="创建" @ok="saveFolder">
      <a-input v-model:value="folderName" :placeholder="space === 'personal' ? '例如：草稿、探索' : '例如：运营周报'" />
    </a-modal>

    <a-modal v-model:open="createOpen" title="在个人空间新建指标" ok-text="保存" @ok="saveMetric">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" />
        </a-form-item>
        <a-form-item label="定义">
          <a-textarea v-model:value="form.definition" :rows="2" />
        </a-form-item>
        <a-form-item label="计算逻辑" required>
          <a-textarea v-model:value="form.calculationLogic" :rows="2" />
        </a-form-item>
        <a-form-item label="来源表">
          <a-select v-model:value="form.sourceTable" :options="tableOpts" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="copyOpen" title="复制到个人目录" ok-text="复制" @ok="doCopy">
      <a-select v-model:value="targetPersonal" style="width: 100%" :options="personalFolderOpts" />
    </a-modal>

    <a-modal v-model:open="pubOpen" :title="pubTarget === 'public' ? '申请成为公共指标' : '发布到业务空间'" ok-text="提交" @ok="doPublish">
      <p v-if="pubTarget === 'public'" class="hint">公共口径需审核通过后才会出现在数据市场。同名已发布的公共指标会被拒绝。</p>
      <a-select v-model:value="targetFolder" style="width: 100%" :options="pubFolderOpts" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import {
  addServeFolder,
  addServeMetric,
  approveServeMetric,
  copyServeMetricToPersonal,
  projectTables,
  publishServeMetric,
  rejectServeMetric,
  visibleServeFolders,
  visibleServeMetrics,
} from '../../stores/app';
import type { ServeScope } from '../../types';

const space = ref<ServeScope>('public');
const folderId = ref(visibleServeFolders('public')[0]?.id ?? '');
const folderOpen = ref(false);
const folderName = ref('');
const createOpen = ref(false);
const copyOpen = ref(false);
const pubOpen = ref(false);
const copyId = ref('');
const pubId = ref('');
const pubTarget = ref<'business' | 'public'>('business');
const targetPersonal = ref('');
const targetFolder = ref('');

const form = reactive({
  name: '',
  definition: '',
  calculationLogic: '',
  sourceTable: projectTables.value.find((t) => t.layer === 'DWS')?.name ?? projectTables.value[0]?.name ?? '',
});

const spaceLabel = computed(() =>
  space.value === 'public' ? '公共（按业务分类）' : space.value === 'business' ? '业务' : '个人（仅自己可见）'
);
const folders = computed(() => visibleServeFolders(space.value));
const rows = computed(() => visibleServeMetrics(space.value).filter((m) => !folderId.value || m.folderId === folderId.value));
const tableOpts = computed(() =>
  projectTables.value
    .filter((t) => t.layer !== 'ODS')
    .map((t) => ({ value: t.name, label: `${t.layer} · ${t.name}` }))
);
const personalFolderOpts = computed(() =>
  visibleServeFolders('personal').map((f) => ({ value: f.id, label: f.name }))
);
const pubFolderOpts = computed(() =>
  visibleServeFolders(pubTarget.value).map((f) => ({ value: f.id, label: f.name }))
);

const cols = [
  { title: '指标', dataIndex: 'name', width: 160 },
  { title: '定义', dataIndex: 'definition' },
  { title: '来源表', dataIndex: 'sourceTable', width: 200 },
  { title: 'Owner', dataIndex: 'owner', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '', key: 'action', width: 220 },
];

function countIn(id: string) {
  return visibleServeMetrics(space.value).filter((m) => m.folderId === id).length;
}

function onSpace() {
  folderId.value = visibleServeFolders(space.value)[0]?.id ?? '';
}

function statusText(s: string) {
  if (s === 'published') return '已发布';
  if (s === 'review') return '待审';
  return '草稿';
}
function statusColor(s: string) {
  if (s === 'published') return 'green';
  if (s === 'review') return 'orange';
  return 'default';
}

function saveFolder() {
  const f = addServeFolder({ scope: space.value, name: folderName.value });
  if (f) {
    folderId.value = f.id;
    folderName.value = '';
    folderOpen.value = false;
  }
}

function saveMetric() {
  const m = addServeMetric({ ...form, folderId: folderId.value });
  if (m) {
    createOpen.value = false;
    form.name = '';
    form.definition = '';
    form.calculationLogic = '';
  }
}

function copyOne(id: string) {
  if (!personalFolderOpts.value.length) {
    message.warning('请先到个人空间建一个目录');
    return;
  }
  copyId.value = id;
  targetPersonal.value = personalFolderOpts.value[0].value;
  copyOpen.value = true;
}

function doCopy() {
  copyServeMetricToPersonal(copyId.value, targetPersonal.value);
  copyOpen.value = false;
}

function publishOne(id: string, target: 'business' | 'public') {
  pubId.value = id;
  pubTarget.value = target;
  targetFolder.value = visibleServeFolders(target)[0]?.id ?? '';
  if (!targetFolder.value) {
    message.warning(target === 'public' ? '还没有公共分类' : '还没有业务目录');
    return;
  }
  pubOpen.value = true;
}

function doPublish() {
  publishServeMetric(pubId.value, pubTarget.value, targetFolder.value);
  pubOpen.value = false;
}
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.split { display: grid; grid-template-columns: 220px 1fr; gap: 12px; align-items: start; }
.tree { padding: 10px; }
.tree-h { font-size: 12px; color: #64748b; margin-bottom: 8px; }
.node {
  display: flex;
  justify-content: space-between;
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}
.node:hover { background: #f1f5f9; }
.node.on { background: #ecfeff; color: #0e7490; }
.node small { color: #94a3b8; }
.grow { min-height: 320px; }
.hint { color: #64748b; font-size: 12px; }
.pad { padding: 10px 12px; }
.gap { margin-left: 10px; }
</style>
