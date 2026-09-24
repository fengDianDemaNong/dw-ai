<template>
  <div class="remote-browser">
    <!--
      逐级定位：dbx 是 连接 → 数据库 → 库，Gravitino 是 metalake → 数据目录 → 库。

      Teleport 到页面顶部那一行，而不是在这里自己再起一个 toolbar ——
      否则「本地元数据」下切换器和筛选器在同一行，切到数据服务就断成两行。

      用 defer：Vue 挂载子组件时，父组件的 DOM 还没插进 document，
      普通 Teleport 的 querySelector 会落空（当前流程下因为是切换后才渲染，
      恰好躲过了，但默认视图一改成远程就会炸）。defer 把目标解析推迟到本轮渲染之后，
      与挂载顺序无关。Vue 3.5 起支持，这里是 3.5.41。
    -->
    <Teleport defer to="#meta-remote-filters">
      <Select
        v-if="isDbx"
        v-model:value="picked.connectionId"
        class="filter-item"
        placeholder="选择 dbx 连接"
        :options="connectionOptions"
        :loading="loadingConnections"
        @change="onConnectionChange"
      />
      <Select
        v-for="level in levels"
        :key="level.key"
        :value="(picked as any)[level.key]"
        class="filter-item"
        :placeholder="level.label"
        :options="level.options"
        :loading="level.loading"
        :disabled="level.disabled"
        allow-clear
        show-search
        @change="(v: any) => onLevelChange(level.key, v)"
      />
      <Input.Search
        v-model:value="keyword"
        class="filter-keyword"
        placeholder="搜索表名"
        allow-clear
      />
    </Teleport>

    <!-- 动作按钮同理，落到顶部那一行的右侧 -->
    <Teleport defer to="#meta-remote-actions">
      <Button
        type="primary"
        class="bg-[#1677ff]"
        :disabled="!checked.length"
        @click="startImport"
      >
        导入选中的 {{ checked.length || '' }} 张表
      </Button>
      <Button :loading="loadingTables" :disabled="!picked.schema" @click="loadTables">
        刷新
      </Button>
    </Teleport>

    <Alert v-if="error" type="error" show-icon closable class="mb-3" :message="error"
           @close="error = ''" />

    <Empty v-if="!picked.schema" class="empty"
           :description="`先选到${schemaLabel}，再看它下面的表`" />

    <div v-else class="split">
      <section class="pane-tables">
        <Table
          :columns="tableColumns"
          :data-source="visibleTables"
          :loading="loadingTables"
          row-key="name"
          size="small"
          :pagination="false"
          :scroll="{ y: 520 }"
          :row-selection="{ selectedRowKeys: checked, onChange: onCheckedChange }"
          :custom-row="rowEvents"
          :row-class-name="rowClass"
        />
      </section>

      <section class="pane-columns">
        <div v-if="!selected" class="empty-hint">点左侧的表名查看字段</div>
        <template v-else>
          <div class="detail-head">
            <span class="detail-title">{{ selected }}</span>
            <span v-if="detail?.comment" class="detail-sub">{{ detail.comment }}</span>
          </div>
          <Table
            :columns="columnColumns"
            :data-source="detail?.columns ?? []"
            :loading="loadingDetail"
            row-key="ordinal"
            size="small"
            :pagination="false"
            :scroll="{ y: 460 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'dataType'">
                <span v-if="record.dataType" class="mono">{{ record.dataType }}</span>
                <span v-else class="muted">—</span>
              </template>
              <template v-else-if="column.key === 'comment'">
                <span v-if="record.comment">{{ record.comment }}</span>
                <span v-else class="muted">—</span>
              </template>
              <template v-else-if="column.key === 'flags'">
                <Tag v-if="record.primaryKey" color="gold">主键</Tag>
                <Tag v-if="record.partition" color="purple">分区</Tag>
                <span v-if="!record.nullable" class="muted">非空</span>
              </template>
            </template>
          </Table>
        </template>
      </section>
    </div>

    <!-- 导入进度：复用与「从元数据服务同步」同一套异步任务 -->
    <Modal v-model:open="importOpen" title="导入中" :footer="null" :closable="!importing">
      <p class="import-line">{{ importMessage }}</p>
      <Progress :percent="importPercent" :status="importFailed ? 'exception' : undefined" />
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue';
import { Alert, Button, Empty, Input, Modal, Progress, Select, Table, Tag, message } from 'ant-design-vue';
import {
  browseMetaSource,
  getMetaSyncJob,
  getRemoteTable,
  listDbxConnections,
  submitMetaSync,
  type MetadataSource,
  type RemoteTableDetail,
} from '../../services/api';

/**
 * 浏览某个元数据服务里的库表。
 *
 * <p>做成独立组件而不是塞进 `pages/meta.vue`：那个文件已经一千多行，
 * 而这一整套（阶梯选择 + 表列表 + 字段 + 导入）本身就是自洽的一块。
 *
 * <p><b>只读</b>。改中文名之类是本地元数据的事，远端改不了 ——
 * 想留下来就导入到本地。
 */
const props = defineProps<{ source: MetadataSource }>();

const isDbx = computed(() => props.source.type === 'DBX');

/** Gravitino 叫 catalog、dbx 叫 database，页面上跟着源类型换称呼。 */
const catalogLabel = computed(() => (isDbx.value ? '数据库' : '数据目录'));
const schemaLabel = computed(() => '库');

const picked = reactive({
  connectionId: undefined as string | undefined,
  metalake: undefined as string | undefined,
  catalog: undefined as string | undefined,
  database: undefined as string | undefined,
  schema: undefined as string | undefined,
});

const connections = ref<{ id: string; name?: string }[]>([]);
const loadingConnections = ref(false);
const connectionOptions = computed(() =>
  connections.value.map((c) => ({ value: c.id, label: c.name ? `${c.name}（${c.id}）` : c.id }))
);

const levelValues = reactive<Record<string, string[]>>({
  metalake: [],
  catalog: [],
  database: [],
  schema: [],
});
const levelLoading = reactive<Record<string, boolean>>({});

/**
 * 两种源的层级不同，用一张表描述而不是写两套模板。
 *
 * `disabled` 表达的是级联依赖：上一级没选，这一级不该能点。
 */
const levels = computed(() => {
  const opts = (k: string) =>
    levelValues[k].map((v) => ({ value: v, label: v }));
  if (isDbx.value) {
    return [
      {
        key: 'database',
        label: catalogLabel.value,
        options: opts('database'),
        loading: !!levelLoading.database,
        disabled: !picked.connectionId,
      },
      {
        key: 'schema',
        label: schemaLabel.value,
        options: opts('schema'),
        loading: !!levelLoading.schema,
        disabled: !picked.database,
      },
    ];
  }
  return [
    {
      key: 'metalake',
      label: 'metalake',
      options: opts('metalake'),
      loading: !!levelLoading.metalake,
      disabled: false,
    },
    {
      key: 'catalog',
      label: catalogLabel.value,
      options: opts('catalog'),
      loading: !!levelLoading.catalog,
      disabled: !picked.metalake,
    },
    {
      key: 'schema',
      label: schemaLabel.value,
      options: opts('schema'),
      loading: !!levelLoading.schema,
      disabled: !picked.catalog,
    },
  ];
});

const tables = ref<string[]>([]);
const loadingTables = ref(false);
const keyword = ref('');
const checked = ref<string[]>([]);
const selected = ref<string | null>(null);
const detail = ref<RemoteTableDetail | null>(null);
const loadingDetail = ref(false);
const error = ref('');

const tableColumns = [{ title: '表名', dataIndex: 'name', key: 'name' }];
const columnColumns = [
  { title: '字段', dataIndex: 'name', key: 'name', width: 200 },
  { title: '类型', key: 'dataType', width: 180 },
  { title: '中文名', key: 'comment' },
  { title: '', key: 'flags', width: 140 },
];

/** 远程接口是整层返回的，没有服务端分页，所以搜索在前端做。 */
const visibleTables = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  const all = tables.value.map((name) => ({ name }));
  return k ? all.filter((t) => t.name.toLowerCase().includes(k)) : all;
});

// ------------------------------------------------------------------

async function browse(level: string, params: Record<string, string | undefined>) {
  levelLoading[level] = true;
  error.value = '';
  try {
    levelValues[level] = await browseMetaSource({
      sourceId: props.source.id,
      connectionId: picked.connectionId,
      ...params,
    });
  } catch (e: any) {
    levelValues[level] = [];
    error.value = e?.message || String(e);
  } finally {
    levelLoading[level] = false;
  }
}

async function loadConnections() {
  loadingConnections.value = true;
  try {
    connections.value = await listDbxConnections(props.source.id);
    // 只有一个连接时直接选上，省一次点击
    if (connections.value.length === 1) {
      picked.connectionId = connections.value[0].id;
      await onConnectionChange();
    }
  } catch (e: any) {
    error.value = e?.message || String(e);
  } finally {
    loadingConnections.value = false;
  }
}

async function onConnectionChange() {
  resetFrom('database');
  await browse('database', {});
}

async function onLevelChange(key: string, value: string | undefined) {
  (picked as any)[key] = value;
  resetFrom(nextOf(key));

  if (!value) return;
  if (key === 'metalake') await browse('catalog', { metalake: value });
  else if (key === 'catalog') await browse('schema', { metalake: picked.metalake, catalog: value });
  else if (key === 'database') await browse('schema', { database: value });
  else if (key === 'schema') await loadTables();
}

function nextOf(key: string): string[] {
  if (key === 'metalake') return ['catalog', 'schema'];
  if (key === 'catalog' || key === 'database') return ['schema'];
  return [];
}

/** 上一级变了，下面各级的已选值与候选都作废，否则会拿旧库名去查新目录。 */
function resetFrom(keys: string[] | string) {
  const list = Array.isArray(keys) ? keys : [keys];
  for (const k of list) {
    (picked as any)[k] = undefined;
    levelValues[k] = [];
  }
  tables.value = [];
  checked.value = [];
  selected.value = null;
  detail.value = null;
}

async function loadTables() {
  if (!picked.schema) return;
  loadingTables.value = true;
  error.value = '';
  try {
    tables.value = await browseMetaSource({
      sourceId: props.source.id,
      connectionId: picked.connectionId,
      metalake: picked.metalake,
      catalog: picked.catalog,
      database: picked.database,
      schema: picked.schema,
    });
    checked.value = [];
    selected.value = null;
    detail.value = null;
  } catch (e: any) {
    tables.value = [];
    error.value = e?.message || String(e);
  } finally {
    loadingTables.value = false;
  }
}

async function openTable(name: string) {
  selected.value = name;
  loadingDetail.value = true;
  detail.value = null;
  try {
    detail.value = await getRemoteTable({
      sourceId: props.source.id,
      table: name,
      connectionId: picked.connectionId,
      metalake: picked.metalake,
      catalog: picked.catalog,
      database: picked.database,
      schema: picked.schema,
    });
  } catch (e: any) {
    error.value = `读取「${name}」的字段失败：` + (e?.message || e);
  } finally {
    loadingDetail.value = false;
  }
}

const rowEvents = (record: { name: string }) => ({ onClick: () => openTable(record.name) });
const rowClass = (record: { name: string }) =>
  record.name === selected.value ? 'row-selected' : '';

function onCheckedChange(keys: (string | number)[]) {
  checked.value = keys.map(String);
}

// ------------------------------------------------------------------
// 导入：复用同步任务，轮询进度

const importOpen = ref(false);
const importing = ref(false);
const importFailed = ref(false);
const importPercent = ref(0);
const importMessage = ref('');

async function startImport() {
  importOpen.value = true;
  importing.value = true;
  importFailed.value = false;
  importPercent.value = 0;
  importMessage.value = `正在导入 ${checked.value.length} 张表…`;

  try {
    const { jobId } = await submitMetaSync({
      sourceId: props.source.id,
      scope: 'TABLE',
      connectionId: picked.connectionId,
      metalake: picked.metalake,
      catalog: picked.catalog,
      database: picked.database,
      schema: picked.schema,
      tables: checked.value,
    });
    await poll(jobId);
  } catch (e: any) {
    importFailed.value = true;
    importMessage.value = '导入失败：' + (e?.message || e);
  } finally {
    importing.value = false;
  }
}

async function poll(jobId: number) {
  for (;;) {
    const job = await getMetaSyncJob(jobId);
    // percent / done 由后端算好，与「从元数据服务同步」那个弹窗用的是同一套字段
    importPercent.value = job.percent;
    importMessage.value = job.total > 0
      ? `已处理 ${job.done} / ${job.total}`
      : '正在统计要导入的表…';

    if (job.finished) {
      importFailed.value = job.status === 'FAILED';
      importMessage.value = job.message
        || `新增 ${job.createdCnt}、更新 ${job.updatedCnt}、跳过 ${job.skippedCnt}`
           + (job.failedCnt ? `、失败 ${job.failedCnt}` : '');
      if (!importFailed.value) {
        message.success('导入完成，切回「本地元数据」即可看到');
        checked.value = [];
      }
      return;
    }
    await new Promise((r) => setTimeout(r, 800));
  }
}

// ------------------------------------------------------------------

/** 换了数据服务就整个重来 —— 上一个源的库名在新源里没有意义。 */
watch(
  () => props.source.id,
  () => {
    picked.connectionId = undefined;
    resetFrom(['metalake', 'catalog', 'database', 'schema']);
    error.value = '';
    if (isDbx.value) {
      loadConnections();
    } else {
      browse('metalake', {});
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.filter-item {
  width: 200px;
}

.filter-keyword {
  width: 220px;
}

.split {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
  min-height: 0;
}

.pane-tables,
.pane-columns {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 8px;
  min-width: 0;
}

.pane-tables :deep(.ant-table-row) {
  cursor: pointer;
}

.pane-tables :deep(.row-selected) > td {
  background: #e6f4ff;
}

.detail-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 4px 8px 10px;
}

.detail-title {
  font-size: 15px;
  font-weight: 600;
}

.detail-sub,
.muted {
  color: #8c8c8c;
  font-size: 12px;
}

.empty,
.empty-hint {
  color: #8c8c8c;
  padding: 40px 0;
  text-align: center;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.import-line {
  margin-bottom: 12px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
