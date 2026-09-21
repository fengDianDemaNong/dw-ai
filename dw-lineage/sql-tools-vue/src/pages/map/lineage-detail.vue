<template>
  <div class="page detail-page">
    <Spin :spinning="loading" wrapper-class-name="page-spin">
      <template v-if="table">
        <PageHeader :title="short(table.fullName)" :subtitle="table.comment || ''">
          <template #actions>
            <Button size="small" @click="openEdit">编辑</Button>
            <Button size="small" danger :loading="checkingRefs" @click="tryDelete">删除</Button>
          </template>
          <template #help>
            <p>
              这张表的<b>血缘</b>侧视图。表名、字段来自 SQL 解析结果；中文名、备注、表类型
              来自元数据目录，按表全名关联 —— 所以「编辑」写的是元数据侧，血缘数据不受影响。
            </p>
            <p>
              <b>血缘图</b>标签页画的是库里已保存的边，不是实时解析的结果。要更新它，
              回「数据地图 › SQL 解析」页重新解析并保存。
            </p>
            <p>
              <b>版本</b>：每次保存都会为目标表产生一个新版本。查图时不选版本就沿各表的当前版本走。
            </p>
          </template>
        </PageHeader>

        <Tabs v-model:activeKey="tab" class="detail-tabs" @change="(k: any) => onTabChange(String(k))">
          <!-- ============ 概览 ============ -->
          <TabPane key="overview" tab="概览">
            <div class="card">
              <Descriptions :column="2" size="small">
                <DescriptionsItem label="数据目录">{{ table.catalogName || '—' }}</DescriptionsItem>
                <DescriptionsItem label="库名">{{ table.schemaName }}</DescriptionsItem>
                <DescriptionsItem label="表名">{{ table.tableName }}</DescriptionsItem>
                <DescriptionsItem label="中文名">{{ table.comment || '—' }}</DescriptionsItem>
                <DescriptionsItem label="表类型">
                  <Tag v-if="table.tableType" color="blue">{{ tableTypeLabel(table.tableType) }}</Tag>
                  <span v-else class="muted">未设置</span>
                </DescriptionsItem>
                <DescriptionsItem label="方言">{{ table.dbType || '—' }}</DescriptionsItem>
                <DescriptionsItem label="元数据">
                  <span v-if="table.metaTableId" class="ok">已关联</span>
                  <Tooltip v-else title="元数据目录里没有这张表的结构，select * 之类的解析会缺列">
                    <span class="muted">缺失</span>
                  </Tooltip>
                </DescriptionsItem>
                <DescriptionsItem label="字段数">{{ columns.length }}</DescriptionsItem>
                <DescriptionsItem label="备注" :span="2">{{ table.remark || '—' }}</DescriptionsItem>
              </Descriptions>
            </div>

            <div class="card">
              <div class="card-title mb-2">上游表</div>
              <template v-if="upstreamTables.length">
                <Tag v-for="t in upstreamTables" :key="t.id" class="link-tag"
                     @click="goTable(t.id)">{{ short(t.fullName) }}</Tag>
              </template>
              <span v-else class="muted">无 —— 这张表没有被解析出上游，是链路的源头</span>
            </div>
          </TabPane>

          <!-- ============ 字段 ============ -->
          <TabPane key="columns" tab="字段">
            <div class="card card-flush">
              <Table
                :columns="columnDefs"
                :data-source="columns"
                :loading="columnsLoading"
                row-key="id"
                size="small"
                :pagination="false"
                :expanded-row-keys="expandedKeys"
                @expand="onExpand"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'dataType'">
                    <span v-if="record.dataType">{{ record.dataType }}</span>
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'comment'">
                    <span v-if="record.comment">{{ record.comment }}</span>
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'partition'">
                    <Tag v-if="record.partition" color="purple">是</Tag>
                    <span v-else class="muted">否</span>
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <Button type="link" size="small" @click.stop="viewColumnLineage(record as CatalogColumn)">
                      看血缘
                    </Button>
                  </template>
                </template>

                <template #expandedRowRender="{ record }">
                  <Spin :spinning="!related[record.id]">
                    <div class="expand-body">
                      <div class="expand-col">
                        <div class="expand-title">上游字段</div>
                        <div v-if="related[record.id]?.up.length" class="expand-list">
                          <Tag v-for="c in related[record.id].up" :key="c.id">
                            {{ short(c.fullName) }}<span v-if="c.comment"> · {{ c.comment }}</span>
                          </Tag>
                        </div>
                        <div v-else-if="related[record.id]" class="muted">无</div>
                      </div>
                      <div class="expand-col">
                        <div class="expand-title">下游字段</div>
                        <div v-if="related[record.id]?.down.length" class="expand-list">
                          <Tag v-for="c in related[record.id].down" :key="c.id">
                            {{ short(c.fullName) }}<span v-if="c.comment"> · {{ c.comment }}</span>
                          </Tag>
                        </div>
                        <div v-else-if="related[record.id]" class="muted">无</div>
                      </div>
                    </div>
                  </Spin>
                </template>
              </Table>
            </div>
          </TabPane>

          <!-- ============ 血缘图 ============ -->
          <TabPane key="lineage" tab="血缘图">
            <div class="lineage-pane">
            <div class="filter-bar">
              <span class="filter-label">范围</span>
              <Select v-model:value="graphForm.columnId" class="w-220" placeholder="整表"
                      show-search allow-clear option-filter-prop="label" :options="columnOptions" />

              <span class="filter-label">方向</span>
              <Select v-model:value="graphForm.direction" class="w-100" :options="directionOptions" />

              <span class="filter-label">层数</span>
              <Select v-model:value="graphForm.depth" class="w-110" :options="depthOptions" />

              <span class="filter-label">版本</span>
              <Tooltip :title="versionHint">
                <Select v-model:value="graphForm.versionId" class="w-200" placeholder="当前版本"
                        allow-clear :options="versionOptions" :disabled="!versions.length" />
              </Tooltip>

              <Button type="primary" class="bg-[#1677ff]" :loading="graphLoading"
                      @click="loadGraph">查询</Button>
            </div>

            <div v-if="graphWarnings.length" class="warning-bar">
              <div v-for="(w, i) in graphWarnings" :key="i">{{ w }}</div>
            </div>

            <div ref="graphBox" class="graph-box">
              <Spin :spinning="graphLoading">
                <LineageGraph
                  v-if="hasGraph"
                  :layout="'preview'"
                  :lineageData="lineageData"
                  v-model:nodeSize="nodeSize"
                  v-model:nodeLevel="nodeLevel"
                  :highlightColor="'red'"
                  :textWaterMarker="preferences.watermark"
                  :isEditorCollapsed="true"
                  :canvasWidth="graphWidth"
                  :canvasHeight="graphHeight"
                  :showMinimap="preferences.showMinimap"
                />
                <div v-else-if="!graphLoading" class="page-empty">
                  <div class="page-empty-text">
                    没有查到血缘数据。确认这张表已经在「数据地图 › SQL 解析」页保存过血缘
                  </div>
                </div>
              </Spin>
              <div v-if="hasGraph && nodeSize" class="graph-stat">
                节点 {{ nodeSize }} · 层数 {{ nodeLevel }}
              </div>
            </div>
            </div>
          </TabPane>

          <!-- ============ 版本 ============ -->
          <TabPane key="versions" tab="版本">
            <div class="card card-flush">
              <Table
                :columns="versionDefs"
                :data-source="versions"
                row-key="id"
                size="small"
                :pagination="false"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'versionNo'">
                    v{{ record.versionNo }}
                    <Tag v-if="record.current" color="green" class="ml-2">当前</Tag>
                  </template>
                  <template v-else-if="column.key === 'createdAt'">
                    <span v-if="record.createdAt">{{ record.createdAt }}</span>
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <Button v-if="!record.current" type="link" size="small"
                            @click="setCurrent(record as LineageVersion)">设为当前</Button>
                    <Popconfirm
                      :title="`删除 v${record.versionNo}？这张表在该版本下的血缘边会一并删除。`"
                      ok-text="删除" cancel-text="取消"
                      @confirm="removeVersion(record as LineageVersion)"
                    >
                      <Button type="link" size="small" danger>删除</Button>
                    </Popconfirm>
                  </template>
                </template>
              </Table>

              <div v-if="!versions.length" class="page-empty">
                <div class="page-empty-text">这张表还没有保存过血缘版本</div>
                <Button type="primary" class="bg-[#1677ff]" @click="router.push('/lineage')">去解析</Button>
              </div>
            </div>
          </TabPane>
        </Tabs>
      </template>

      <div v-else-if="!loading" class="page-empty">
        <div class="page-empty-text">表不存在，可能已被删除</div>
        <Button @click="router.push('/lineage/tables')">回到数据表列表</Button>
      </div>
    </Spin>

    <!-- 编辑表的描述属性（写的是元数据侧） -->
    <Modal v-model:open="editOpen" title="编辑表信息" :confirm-loading="saving"
           ok-text="保存" cancel-text="取消" @ok="save">
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }" class="mt-3">
        <FormItem label="表">
          <Input :value="short(editForm.fullName)" disabled />
        </FormItem>
        <FormItem label="表类型">
          <Select v-model:value="editForm.tableType" :options="tableTypeOptions"
                  allow-clear placeholder="未设置" />
        </FormItem>
        <FormItem label="中文名">
          <Input v-model:value="editForm.comment" />
        </FormItem>
        <FormItem label="备注">
          <Textarea v-model:value="editForm.remark" :rows="3" />
        </FormItem>
        <FormItem :wrapper-col="{ offset: 5, span: 19 }">
          <div class="hint">
            中文名/备注/表类型属于<b>元数据</b>，保存后写入元数据目录并标记为「手工维护」，
            后续同步不会覆盖。血缘数据本身不受影响。
          </div>
        </FormItem>
      </Form>
    </Modal>

    <!-- 删除前的引用提示 -->
    <Modal v-model:open="refsOpen" title="下游依赖项提示" :footer="null" width="720px">
      <p class="hint mb-2">
        该表已被下列字段引用，删除后这些血缘会指向一张不存在的表。请先处理下游依赖。
      </p>
      <Table :columns="refColumnDefs" :data-source="references" row-key="rowKey"
             size="small" :pagination="false" :scroll="{ y: 320 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'fullName'">{{ short(record.fullName) }}</template>
        </template>
      </Table>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Button, Descriptions, Form, Input, Modal, Popconfirm, Select,
  Spin, Table, Tabs, Tag, Tooltip, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import LineageGraph from '../../components/LineageGraph/index.vue';
import { displayName } from '../../utils/common';
import { catalogState } from '../../stores/catalog';
import { uiState } from '../../stores/ui';
import { preferences } from '../../stores/preferences';
import { tableTypeLabel, tableTypeOptions } from '../../config/tableTypes';
import {
  deleteLineageVersion, getCatalogTable, getSavedLineageGraph, listCatalogColumns,
  listDownstreamColumns, listLineageVersions, listTableReferences, listUpstreamColumns,
  listUpstreamTables, markVersionCurrent, updateMetaTable,
} from '../../services/api';
import type {
  CatalogColumn, CatalogSearchHit, CatalogTable, LineageVersion,
} from '../../services/api';

/**
 * 表详情。
 *
 * 改版前这些内容分在两个页面：「表基础信息」（表 + 字段 + 上游）和「血缘关系」（图）。
 * 两边都要求先做「数据目录 → 库 → 表」三级级联才出内容，是同一件事做了两遍；
 * 前者页面底部还挂着一个「在血缘关系页查看」的链接，等于自己承认了这一点。
 * 这里合成一个页面按 id 打开，级联全部消失。
 */
const FormItem = Form.Item;
const Textarea = Input.TextArea;
const DescriptionsItem = Descriptions.Item;
const TabPane = Tabs.TabPane;

const route = useRoute();
const router = useRouter();

const TABS = ['overview', 'columns', 'lineage', 'versions'];

const short = (name: string) => displayName(name, catalogState.defaultCatalog);

const columnDefs = [
  // 行号而不是 ordinal：血缘侧的字段是从 SQL 解析出来的，没有表定义里的列序，
  // ordinal 恒为 0，整列显示一串 0 既没用又像是坏了
  {
    title: '#',
    key: 'index',
    width: 56,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  { title: '字段', dataIndex: 'columnName', key: 'columnName', width: 220 },
  { title: '类型', key: 'dataType', width: 160 },
  { title: '中文名', key: 'comment' },
  { title: '分区字段', key: 'partition', width: 100 },
  { title: '操作', key: 'action', width: 90 },
];

const refColumnDefs = [
  { title: '下游表', dataIndex: 'fullName', key: 'fullName' },
  { title: '下游字段', dataIndex: 'columnName', key: 'columnName' },
  { title: '引用了本表的字段', dataIndex: 'columnComment', key: 'columnComment' },
];

const versionDefs = [
  { title: '版本', key: 'versionNo', width: 140 },
  { title: '方言', dataIndex: 'dbType', key: 'dbType', width: 120 },
  { title: '表', dataIndex: 'statTables', key: 'statTables', width: 90 },
  { title: '字段', dataIndex: 'statColumns', key: 'statColumns', width: 90 },
  { title: '边', dataIndex: 'statEdges', key: 'statEdges', width: 90 },
  { title: '生成时间', key: 'createdAt' },
  { title: '操作', key: 'action', width: 160, fixed: 'right' as const },
];

const tableId = computed(() => Number(route.params.id));

const table = ref<CatalogTable | null>(null);
const columns = ref<CatalogColumn[]>([]);
const upstreamTables = ref<CatalogTable[]>([]);
const versions = ref<LineageVersion[]>([]);
const references = ref<(CatalogSearchHit & { rowKey: string })[]>([]);

const loading = ref(false);
const columnsLoading = ref(false);
const checkingRefs = ref(false);
const refsOpen = ref(false);

const tab = ref('overview');

// ------------------------------------------------------------------
// 字段展开行：上下游都拉，一次缓存住
// ------------------------------------------------------------------

const related = reactive<Record<number, { up: CatalogColumn[]; down: CatalogColumn[] }>>({});
const expandedKeys = ref<number[]>([]);

async function onExpand(expanded: boolean, record: CatalogColumn) {
  expandedKeys.value = expanded
    ? [...expandedKeys.value, record.id]
    : expandedKeys.value.filter((k) => k !== record.id);

  if (!expanded || related[record.id]) return;
  try {
    const [up, down] = await Promise.all([
      listUpstreamColumns(record.id),
      listDownstreamColumns(record.id),
    ]);
    related[record.id] = { up, down };
  } catch (e: any) {
    message.error('加载字段血缘失败：' + (e?.message || e));
    related[record.id] = { up: [], down: [] };
  }
}

// ------------------------------------------------------------------
// 血缘图
// ------------------------------------------------------------------

const directionOptions = [
  { value: 'up', label: '上游' },
  { value: 'down', label: '下游' },
];
/** 默认 2 层，0 表示不限（后端仍有硬上限兜底）。 */
const depthOptions = [
  { value: 0, label: '全部' },
  { value: 2, label: '2 层' },
  { value: 3, label: '3 层' },
  { value: 4, label: '4 层' },
  { value: 5, label: '5 层' },
];

const versionHint =
  '不选则沿各表的当前版本。选定历史版本时，只有起点表用该版本的边，' +
  '上游表仍走它们各自的当前版本 —— 上下游的历史版本之间没有对应关系，' +
  '硬拼会得到一张从未真实存在过的图。';

const graphForm = reactive({
  columnId: undefined as number | undefined,
  direction: 'up' as 'up' | 'down',
  depth: 2,
  versionId: undefined as number | undefined,
});

const lineageData = ref<any>({});
const graphWarnings = ref<string[]>([]);
const graphLoading = ref(false);
const graphLoaded = ref(false);
const nodeSize = ref(0);
const nodeLevel = ref(0);

const columnOptions = computed(() =>
  columns.value.map((c) => ({
    value: c.id,
    label: c.comment ? `${c.columnName}（${c.comment}）` : c.columnName,
  }))
);

const versionOptions = computed(() =>
  versions.value.map((v) => ({
    value: v.id,
    label: `v${v.versionNo}${v.current ? '（当前）' : ''} · ${v.statEdges} 条边`,
  }))
);

const hasGraph = computed(() => !!lineageData.value?.withProcessData?.data?.length);

/**
 * 画布尺寸。
 *
 * 量的是放画布那个盒子，不是整屏 —— 左边有侧边栏、上面有顶栏和筛选条，
 * 按整屏算画布会溢出到看不见的地方。
 */
const graphBox = ref<HTMLElement>();
const graphWidth = ref(0);
const graphHeight = ref(0);
let graphObserver: ResizeObserver | undefined;

function measureGraph() {
  const el = graphBox.value;
  if (!el) return;
  graphWidth.value = el.clientWidth;
  graphHeight.value = el.clientHeight;
}

async function loadGraph() {
  if (!table.value) return;

  // 选了字段就以该字段为起点，否则整表
  let start = table.value.fullName;
  if (graphForm.columnId) {
    const column = columns.value.find((c) => c.id === graphForm.columnId);
    if (column) start = column.fullName;
  }

  graphLoading.value = true;
  graphWarnings.value = [];
  try {
    const res = await getSavedLineageGraph({
      start,
      direction: graphForm.direction,
      depth: graphForm.depth,
      versionId: graphForm.versionId,
    });
    if (res && (res.code === 0 || res.code === 200)) {
      lineageData.value = res.data || {};
      graphWarnings.value = res.warnings || [];
      const section = lineageData.value.withProcessData;
      if (section) {
        nodeSize.value = section.size || 0;
        nodeLevel.value = section.level || 0;
      }
      graphLoaded.value = true;
    } else {
      throw new Error(res?.message || '未知错误');
    }
  } catch (e: any) {
    message.error('查询血缘失败：' + (e?.message || e));
  } finally {
    graphLoading.value = false;
  }
}

// ------------------------------------------------------------------
// 版本
// ------------------------------------------------------------------

async function setCurrent(version: LineageVersion) {
  try {
    await markVersionCurrent(version.id);
    message.success(`已把 v${version.versionNo} 设为当前版本`);
    versions.value = await listLineageVersions(tableId.value);
    // 当前版本变了，已经画出来的图就不是「当前」的了
    if (graphLoaded.value) await loadGraph();
  } catch (e: any) {
    message.error('操作失败：' + (e?.message || e));
  }
}

async function removeVersion(version: LineageVersion) {
  try {
    await deleteLineageVersion(version.id);
    message.success(`已删除 v${version.versionNo}`);
    versions.value = await listLineageVersions(tableId.value);
    if (graphForm.versionId === version.id) graphForm.versionId = undefined;
    if (graphLoaded.value) await loadGraph();
  } catch (e: any) {
    message.error('删除失败：' + (e?.message || e));
  }
}

// ------------------------------------------------------------------
// 编辑 / 删除
// ------------------------------------------------------------------

const editOpen = ref(false);
const saving = ref(false);
const editForm = reactive({
  fullName: '', tableType: undefined as string | undefined, comment: '', remark: '',
});

function openEdit() {
  if (!table.value) return;
  editForm.fullName = table.value.fullName;
  editForm.tableType = table.value.tableType || undefined;
  editForm.comment = table.value.comment || '';
  editForm.remark = table.value.remark || '';
  editOpen.value = true;
}

async function save() {
  if (!table.value) return;
  if (!table.value.metaTableId) {
    // 元数据目录里没有这张表（比如临时表），没有可挂载描述属性的地方
    message.error('这张表还不在元数据目录里，请先去「元数据」页面导入或同步它');
    return;
  }
  saving.value = true;
  try {
    await updateMetaTable(table.value.metaTableId, {
      tableType: editForm.tableType,
      comment: editForm.comment,
      remark: editForm.remark,
    });
    message.success('已保存');
    editOpen.value = false;
    await load();
  } catch (e: any) {
    message.error('保存失败：' + (e?.message || e));
  } finally {
    saving.value = false;
  }
}

async function tryDelete() {
  if (!table.value) return;
  checkingRefs.value = true;
  try {
    const refs = await listTableReferences(table.value.id);
    if (refs.length) {
      references.value = refs.map((r, i) => ({ ...r, rowKey: `${r.fullName}-${r.columnName}-${i}` }));
      refsOpen.value = true;
      return;
    }
    // 血缘目录的表没有独立的删除入口：它由解析结果自动维护，手工删掉下次解析还会回来。
    // 真正要清理的是版本，在「版本」标签页做
    message.info('该表没有下游引用。血缘目录由解析结果自动维护，如需清理请到「版本」标签页删除对应版本');
    tab.value = 'versions';
  } catch (e: any) {
    message.error('检查引用失败：' + (e?.message || e));
  } finally {
    checkingRefs.value = false;
  }
}

// ------------------------------------------------------------------
// 加载与路由
// ------------------------------------------------------------------

function goTable(id: number) {
  router.push(`/lineage/tables/${id}`);
}

function viewColumnLineage(column: CatalogColumn) {
  graphForm.columnId = column.id;
  tab.value = 'lineage';
  onTabChange('lineage');
}

function onTabChange(key: string) {
  router.replace({ query: { ...route.query, tab: key } });
  // 血缘图按需查：进详情就自动查一次的话，只想看字段的人也白等一次图
  if (key === 'lineage' && !graphLoaded.value && !graphLoading.value) void loadGraph();
  if (key === 'lineage') requestAnimationFrame(measureGraph);
}

async function load() {
  const id = tableId.value;
  if (!Number.isFinite(id)) return;

  loading.value = true;
  columnsLoading.value = true;
  expandedKeys.value = [];
  Object.keys(related).forEach((k) => delete related[Number(k)]);

  try {
    const [detail, cols, ups, vers] = await Promise.all([
      getCatalogTable(id),
      listCatalogColumns(id),
      listUpstreamTables(id),
      listLineageVersions(id),
    ]);
    table.value = detail;
    columns.value = cols;
    upstreamTables.value = ups;
    versions.value = vers;
    uiState.crumb = short(detail.fullName);
  } catch (e: any) {
    message.error('加载表信息失败：' + (e?.message || e));
    table.value = null;
  } finally {
    loading.value = false;
    columnsLoading.value = false;
  }
}

/** 换表时把上一张表的图和筛选一起清掉，否则会看到别的表的血缘。 */
function resetGraphState() {
  lineageData.value = {};
  graphWarnings.value = [];
  graphLoaded.value = false;
  nodeSize.value = 0;
  nodeLevel.value = 0;
  graphForm.columnId = undefined;
  graphForm.versionId = undefined;
}

watch(tableId, async () => {
  resetGraphState();
  await load();
  if (tab.value === 'lineage') void loadGraph();
});

// 侧边栏折叠、窗口缩放都会改内容区宽度，画布跟着重量
watch(() => [uiState.contentWidth, uiState.contentHeight], () => requestAnimationFrame(measureGraph));

onMounted(async () => {
  const wanted = route.query.tab as string | undefined;
  if (wanted && TABS.includes(wanted)) tab.value = wanted;

  await load();

  graphObserver = new ResizeObserver(measureGraph);
  if (graphBox.value) graphObserver.observe(graphBox.value);
  measureGraph();

  if (tab.value === 'lineage') void loadGraph();
});

onUnmounted(() => {
  graphObserver?.disconnect();
  uiState.crumb = '';
});
</script>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/*
 * Spin 会在自己和内容之间插一层 div。不给这层高度，整条高度链在这里断掉，
 * 下面的 Tab 和画布就只剩内容高度 —— 表现为画布缩在页面上半截、下面一片空白。
 */
.detail-page :deep(.page-spin),
.detail-page :deep(.page-spin > .ant-spin-container) {
  height: 100%;
  min-height: 0;
}

.detail-page :deep(.page-spin > .ant-spin-container) {
  display: flex;
  flex-direction: column;
}

.detail-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-tabs :deep(.ant-tabs-content-holder) {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.detail-tabs :deep(.ant-tabs-content),
.detail-tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
}

/* 血缘图这一页不跟着滚，画布自己占满剩下的高度 */
.lineage-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.w-100 { width: 100px; }
.w-110 { width: 110px; }
.w-200 { width: 200px; }
.w-220 { width: 220px; }

.mb-2 { margin-bottom: 8px; }
.ml-2 { margin-left: 8px; }
.mt-3 { margin-top: 12px; }

.ok {
  color: #16a34a;
}

.link-tag {
  cursor: pointer;
}

.link-tag:hover {
  color: #1677ff;
  border-color: #1677ff;
}

.expand-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 4px 8px;
}

.expand-title {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 6px;
}

.expand-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.warning-bar {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 6px;
  padding: 6px 12px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #874d00;
}

.graph-box {
  position: relative;
  /* 画布要有确定高度才画得出来，所以这里 flex:1 而不是让内容撑 */
  flex: 1;
  min-height: 360px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  overflow: hidden;
}

.graph-box :deep(.ant-spin-nested-loading),
.graph-box :deep(.ant-spin-container) {
  height: 100%;
}

.graph-stat {
  position: absolute;
  left: 12px;
  bottom: 12px;
  z-index: 50;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  color: #4b5563;
  pointer-events: none;
}
</style>
