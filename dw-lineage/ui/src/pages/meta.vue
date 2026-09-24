<template>
  <ConfigProvider :wave="{ disabled: true }">
    <div class="meta-page">
      <main class="page-body">
        <PageHeader title="元数据" :subtitle="headerSubtitle">
          <template #actions>
            <!-- 这三个都只对本地目录有意义：前两个是往本地导数据，
                 刷新刷的也是本地列表。远程模式下由 RemoteMetaBrowser 自带刷新 -->
            <template v-if="isLocalView">
              <Button :disabled="!canAdmin" @click="openDdlImport">贴建表语句导入</Button>
              <Button :disabled="!canAdmin" @click="openSync">从元数据服务同步</Button>
              <Button @click="reload" :loading="loading">刷新</Button>
            </template>
          </template>
          <template #help>
            <p>
              与血缘数据<b>分开存储</b>：元数据是数据库里真实存在的表结构，
              血缘是从 SQL 推导出来的（可能含临时表和推断出来的列）。
              混在一起的话，一次错误推断会污染元数据，而元数据又反过来喂给解析器。
            </p>
            <p>
              要让它参与解析，在「设置 › 元数据服务」页面确认<b>本地元数据目录</b>
              处于启用状态并排好优先级。
            </p>
            <p>
              想看某张表的<b>血缘</b>而不是结构，去「数据地图 › 血缘」。
            </p>
          </template>
        </PageHeader>

        <!-- 只读访客进得来这一页（入口权限是 catalog:read），但下面的写操作服务端要
             catalog:admin。不解释的话按钮点了没反应，会被当成功能坏了 -->
        <Alert
          v-if="!canAdmin"
          type="info"
          show-icon
          class="mb-3"
          message="当前角色只能查看元数据，不能导入、同步或修改表结构。需要编辑请联系项目管理员。"
        />

        <div class="toolbar">
          <div class="toolbar-filters">
            <!-- 看本地目录还是某个数据服务。默认本地，与切换前的行为一致 -->
            <Select
              v-model:value="viewSourceId"
              class="filter-schema view-switch"
              :options="viewOptions"
              :loading="viewSourcesLoading"
            />
            <Select
              v-if="isLocalView"
              v-model:value="filters.catalog"
              class="filter-schema"
              placeholder="全部数据目录"
              allow-clear
              :options="catalogOptions"
              :loading="catalogsLoading"
              @change="onCatalogChange"
            />
            <Select
              v-if="isLocalView"
              v-model:value="filters.schema"
              class="filter-schema"
              placeholder="全部库"
              allow-clear
              :options="schemaOptions"
              @change="reload"
            />
            <Input.Search
              v-if="isLocalView"
              v-model:value="filters.keyword"
              placeholder="搜索表名或中文名"
              class="filter-keyword"
              allow-clear
              @search="reload"
            />
            <!--
              远程模式下 RemoteMetaBrowser 把它的层级选择器 Teleport 到这里。
              不这么做的话它只能在自己的组件里另起一个 toolbar，
              于是切换器一行、库表选择器又一行 —— 本地模式是一行，切过去就断开。
              用 display:contents 让传进来的元素直接参与这一行的 flex 布局。
            -->
            <span id="meta-remote-filters" class="teleport-inline"></span>
          </div>
          <div id="meta-remote-actions" class="toolbar-actions"></div>
        </div>

        <div v-if="isLocalView" class="split">
          <section class="pane-tables">
            <Table
              :columns="tableColumns"
              :data-source="tables"
              :loading="loading"
              row-key="id"
              size="small"
              :pagination="pagination"
              :custom-row="rowEvents"
              :row-class-name="rowClass"
              @change="onTableChange"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'">
                  <div class="table-name">{{ record.tableName }}</div>
                  <!-- 带上数据目录：两个目录下可能有同名的 schema.table，只显示库名分不清 -->
                  <div class="table-sub">
                    <span v-if="record.catalogName && record.catalogName !== catalogState.defaultCatalog"
                          class="table-catalog">{{ record.catalogName }} / </span>{{ record.schemaName }}<span v-if="record.comment"> · {{ record.comment }}</span>
                  </div>
                </template>
                <template v-else-if="column.key === 'source'">
                  <Tag :color="sourceColor(record.source)">{{ sourceLabel(record.source) }}</Tag>
                </template>
                <template v-else-if="column.key === 'action'">
                  <Button :disabled="!canAdmin" type="link" size="small" @click.stop="openEditTable(record as MetaTable)">编辑</Button>
                  <Popconfirm
                    :title="`删除「${short(record.fullName)}」？已保存的血缘不受影响。`"
                    ok-text="删除"
                    cancel-text="取消"
                    @confirm="removeTable(record as MetaTable)"
                  >
                    <Button :disabled="!canAdmin" type="link" size="small" danger @click.stop>删除</Button>
                  </Popconfirm>
                </template>
              </template>
            </Table>
          </section>

          <section class="pane-columns">
            <div v-if="!selected" class="empty-hint">在左侧选择一张表，查看它的字段</div>
            <template v-else>
              <div class="detail-head">
                <div>
                  <span class="detail-title">{{ short(selected.fullName) }}</span>
                  <Tag :color="sourceColor(selected.source)" class="ml-2">{{ sourceLabel(selected.source) }}</Tag>
                  <Tag v-if="selected.tableType">{{ tableTypeLabel(selected.tableType) }}</Tag>
                </div>
                <div class="detail-sub">
                  <span v-if="selected.comment">{{ selected.comment }}</span>
                  <span v-if="selected.syncedAt" class="detail-synced">
                    最近同步 {{ formatTime(selected.syncedAt) }}
                  </span>
                </div>
              </div>
              <Table
                :columns="columnColumns"
                :data-source="columns"
                :loading="columnsLoading"
                row-key="id"
                size="small"
                :pagination="false"
                :scroll="{ y: 420 }"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'partition'">
                    <Tag v-if="record.partition" color="purple">分区</Tag>
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'comment'">
                    <span v-if="record.comment">{{ record.comment }}</span>
                    <span v-else class="muted">—</span>
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <Button :disabled="!canAdmin" type="link" size="small" @click="openEditColumn(record as MetaColumn)">编辑</Button>
                  </template>
                </template>
              </Table>
            </template>
          </section>
        </div>

        <!-- 远程浏览整块在独立组件里，这里只负责挑一个源交给它 -->
        <RemoteMetaBrowser v-else-if="viewSource" :source="viewSource" />
      </main>

      <!-- 贴建表语句导入 -->
      <Modal v-model:open="ddlOpen" title="贴建表语句导入" width="720px"
             :confirm-loading="ddlSubmitting" ok-text="导入" cancel-text="取消" @ok="submitDdl">
        <Form layout="vertical" class="mt-3">
          <FormItem label="方言">
            <Select v-model:value="ddlForm.dbType" :options="dbTypeOptions" style="width: 200px" />
          </FormItem>
          <FormItem label="导入到哪个数据目录">
            <Select
              v-model:value="ddlForm.catalogName"
              :options="dataCatalogOptions"
              :loading="dataCatalogsLoading"
              style="width: 260px"
            />
            <div class="field-hint">
              默认选中默认数据目录。建表语句自己写了三段名（<code>目录.库.表</code>）时<b>以语句为准</b> ——
              一次粘进多个目录的建表语句是常见做法，不该被这里的选择挤到一起。
              目录在「数据地图 › 数据目录」里维护。
            </div>
          </FormItem>
          <FormItem label="建表语句">
            <Textarea v-model:value="ddlForm.ddl" :rows="12"
                      placeholder="CREATE TABLE ods.user_log (id BIGINT COMMENT '用户主键', ...) COMMENT '用户行为日志' PARTITIONED BY (pt STRING COMMENT '日期分区');" />
            <div class="field-hint">
              可以直接把整个脚本粘进来，非建表语句会被忽略。字段类型、注释、分区列都会一并导入。
            </div>
          </FormItem>
          <FormItem>
            <Checkbox v-model:checked="ddlForm.overwriteManual">覆盖人工维护过的内容</Checkbox>
            <div class="field-hint">
              不勾选时，手工改过的表会被跳过 —— 免得你刚补好的中文名被这次导入冲掉。
            </div>
          </FormItem>
        </Form>
      </Modal>

      <!-- 从元数据服务同步：左边选源、右边选目标；提交后切成进度视图 -->
      <Modal v-model:open="syncOpen" title="从元数据服务导入" width="960px"
             :confirm-loading="syncSubmitting"
             :ok-text="syncJob ? '关闭' : '开始导入'"
             :cancel-text="syncJob ? '后台运行' : '取消'"
             @ok="syncJob ? closeSync() : submitSync()"
             @cancel="closeSync">

        <!-- ① 配置阶段 -->
        <div v-if="!syncJob" class="sync-panes">
          <section class="sync-pane">
            <div class="sync-pane-title">从哪里导入</div>
            <Form layout="vertical">
              <FormItem label="元数据服务">
                <Select v-model:value="syncForm.sourceId" :options="syncSourceOptions"
                        placeholder="选择一个 Gravitino 或 dbx 服务" @change="onSyncSourceChange" />
                <div v-if="!syncSourceOptions.length" class="field-hint">
                  还没有可导入的外部服务。先去「设置 › 元数据服务」页面配置一个 Gravitino 或 dbx。
                </div>
              </FormItem>

              <!-- dbx 的连接：从 dbx 已保存的连接里选，不用再手写 extraConfig -->
              <FormItem v-if="isDbxSource" label="连接">
                <Select v-model:value="syncForm.connectionId" :options="connectionOptions"
                        :loading="connectionsLoading" placeholder="选择 dbx 中已保存的连接"
                        @change="onConnectionChange" />
                <div v-if="!connectionsLoading && !connectionOptions.length" class="field-hint">
                  这个 dbx 里还没有已保存的连接。未保存的连接只存在于建立它的那个会话里，
                  跨进程用不了 —— 请先在 dbx 界面上保存一个。
                </div>
              </FormItem>

              <FormItem v-for="level in levels" :key="level.key" :label="level.label">
                <Select
                  :value="(syncForm as any)[level.key]"
                  :options="level.options.map((o) => ({ value: o, label: o }))"
                  :loading="level.loading"
                  :disabled="level.disabled"
                  allow-clear show-search placeholder="选择"
                  @change="(v: any) => onLevelChange(level.key, v)"
                />
              </FormItem>

              <FormItem label="导入范围">
                <RadioGroup v-model:value="syncForm.scope" @change="onScopeChange">
                  <Radio value="CATALOG" :disabled="!scopeRoot">整个{{ catalogLevelLabel }}</Radio>
                  <Radio value="SCHEMA" :disabled="!syncForm.schema">整个库</Radio>
                  <Radio value="TABLE">指定表</Radio>
                </RadioGroup>
                <div class="field-hint">
                  按{{ catalogLevelLabel }}或按库导入时不用逐个勾表，任务会自己展开。
                </div>
              </FormItem>

              <FormItem v-if="syncForm.scope === 'TABLE' && tableChoices.length"
                        :label="`表（已选 ${syncForm.tables.length} / ${tableChoices.length}）`">
                <div class="table-picker">
                  <Checkbox :checked="allTablesChecked" :indeterminate="someTablesChecked"
                            @change="toggleAllTables">全选</Checkbox>
                  <CheckboxGroup v-model:value="syncForm.tables" class="table-checkboxes">
                    <Checkbox v-for="t in tableChoices" :key="t" :value="t">{{ t }}</Checkbox>
                  </CheckboxGroup>
                </div>
              </FormItem>
            </Form>
          </section>

          <section class="sync-pane">
            <div class="sync-pane-title">导入到哪里</div>
            <Form layout="vertical">
              <FormItem label="目标数据目录">
                <Select v-model:value="syncForm.targetCatalog" :options="targetCatalogOptions"
                        placeholder="默认与源端一致" allow-clear show-search
                        :filter-option="false" mode="tags" :max-tag-count="1"
                        @search="onTargetCatalogSearch" />
                <div class="field-hint">
                  可以选已有的，也可以直接输入新建。留空则沿用源端的
                  {{ sourceCatalogName || '数据目录' }}。
                </div>
              </FormItem>

              <FormItem label="库名">
                <Input :value="syncForm.schema || '（跟随源端）'" disabled />
                <div class="field-hint">
                  库名与表名<b>不可改</b>：改了之后 SQL 解析按 schema.table 就对不上，
                  元数据也就失去了意义。
                </div>
              </FormItem>

              <FormItem label="预览">
                <div class="sync-preview">{{ importPreview }}</div>
              </FormItem>

              <FormItem>
                <Checkbox v-model:checked="syncForm.overwriteManual">覆盖人工维护过的内容</Checkbox>
                <div class="field-hint">
                  不勾选时，手工改过的表会被跳过 —— 免得你刚补好的中文名被这次导入冲掉。
                </div>
              </FormItem>
            </Form>
          </section>
        </div>

        <!-- ② 进度阶段 -->
        <div v-else class="sync-progress">
          <Progress :percent="syncJob.percent"
                    :status="syncJob.status === 'FAILED' ? 'exception'
                             : (syncJob.finished ? 'success' : 'active')" />
          <div class="sync-progress-line">
            <template v-if="syncJob.total > 0">已处理 {{ syncJob.done }} / {{ syncJob.total }}</template>
            <template v-else-if="!syncJob.finished">正在统计要导入的表…</template>
            <span class="sync-status-tag">{{ syncStatusLabel }}</span>
          </div>
          <div class="sync-counts">
            <Tag color="green">新增 {{ syncJob.createdCnt }}</Tag>
            <Tag color="blue">更新 {{ syncJob.updatedCnt }}</Tag>
            <Tag>跳过 {{ syncJob.skippedCnt }}</Tag>
            <Tag v-if="syncJob.failedCnt" color="red">失败 {{ syncJob.failedCnt }}</Tag>
          </div>
          <Alert v-if="syncJob.message" type="warning" show-icon class="mt-3"
                 :message="syncJob.message" />
          <div v-if="!syncJob.finished" class="field-hint mt-3">
            导入在服务端执行，关掉这个弹窗不会中断它 —— 可以稍后从「从元数据服务导入」再看进度。
          </div>
          <div v-if="syncJob.failures?.length" class="sync-failures">
            <div class="sync-failures-title">失败明细</div>
            <div v-for="(f, i) in syncJob.failures" :key="i" class="sync-failure-item">{{ f }}</div>
          </div>
        </div>
      </Modal>

      <!-- 编辑表 -->
      <Modal v-model:open="tableEditOpen" title="编辑表信息" :confirm-loading="savingTable"
             ok-text="保存" cancel-text="取消" @ok="saveTable">
        <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }" class="mt-3">
          <FormItem label="表">
            <Input :value="short(tableEditForm.fullName)" disabled />
          </FormItem>
          <FormItem label="表类型">
            <Select v-model:value="tableEditForm.tableType" :options="tableTypeOptions" allow-clear placeholder="未设置" />
          </FormItem>
          <FormItem label="中文名">
            <Input v-model:value="tableEditForm.comment" />
          </FormItem>
          <FormItem label="备注">
            <Textarea v-model:value="tableEditForm.remark" :rows="3" />
          </FormItem>
          <FormItem :wrapper-col="{ offset: 5, span: 19 }">
            <div class="field-hint">保存后该表标记为「手工维护」，后续同步默认不再覆盖它。</div>
          </FormItem>
        </Form>
      </Modal>

      <!-- 编辑字段 -->
      <Modal v-model:open="columnEditOpen" title="编辑字段" :confirm-loading="savingColumn"
             ok-text="保存" cancel-text="取消" @ok="saveColumn">
        <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }" class="mt-3">
          <FormItem label="字段">
            <Input :value="columnEditForm.columnName" disabled />
          </FormItem>
          <FormItem label="类型">
            <Input v-model:value="columnEditForm.dataType" />
          </FormItem>
          <FormItem label="中文名">
            <Input v-model:value="columnEditForm.comment" />
          </FormItem>
          <FormItem label="备注">
            <Textarea v-model:value="columnEditForm.remark" :rows="3" />
          </FormItem>
        </Form>
      </Modal>
    </div>
  </ConfigProvider>
</template>

<script lang="ts" setup>
import PageHeader from '../components/PageHeader/index.vue';
import RemoteMetaBrowser from '../components/RemoteMetaBrowser/index.vue';
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import {
  Alert, Button, Checkbox, ConfigProvider, Form, Input, Modal, Progress, Radio,
  Popconfirm, Select, Table, Tag, message,
} from 'ant-design-vue';
import { displayName } from '../utils/common';
import { catalogState } from '../stores/catalog';
import { tableTypeOptions, tableTypeLabel } from '../config/tableTypes';
import { can } from '../config/iam';
import {
  browseMetaSource, deleteMetaTable, getDbTypes, importMetaDdl, listDataCatalogs, listMetaColumns,
  getMetaSyncJob, listDbxConnections, listMetaCatalogs, listMetaSchemas, listMetaTables,
  listMetadataSources, submitMetaSync,
  updateMetaColumn, updateMetaTable,
} from '../services/api';
import type {
  DataCatalog, DbxConnection, MetaColumn, MetaSource, MetaTable, MetaWriteResult,
  MetadataSource, SyncJob,
} from '../services/api';

const FormItem = Form.Item;
const Textarea = Input.TextArea;

/**
 * 本页的写操作（导入 DDL、从元数据服务同步、改表/字段的描述）服务端一律要 catalog:admin，
 * 而本页的入口权限是 catalog:read —— 只读访客进得来，所以按钮置灰而不是藏起来：
 * 让他知道有这些功能、是自己权限不够，而不是功能不存在。
 */
const canAdmin = can('catalog:admin');
const CheckboxGroup = Checkbox.Group;
const RadioGroup = Radio.Group;

// ---------------- 列表 ----------------

const tableColumns = [
  { title: '表', key: 'name' },
  { title: '来源', key: 'source', width: 100 },
  { title: '操作', key: 'action', width: 120 },
];

const columnColumns = [
  { title: '#', dataIndex: 'ordinal', key: 'ordinal', width: 50 },
  { title: '字段', dataIndex: 'columnName', key: 'columnName', width: 180 },
  { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 140 },
  { title: '中文名', key: 'comment' },
  { title: '分区', key: 'partition', width: 70 },
  { title: '操作', key: 'action', width: 70 },
];

/** 顶部说明是否展开。默认收起，别让每次进页面都先读一段字。 */

/** 展示用短名：默认数据目录那一段隐去。只用于渲染，请求参数一律用完整 fullName。 */
const short = (name: string) => displayName(name, catalogState.defaultCatalog);

/**
 * 当前在看哪一份元数据。
 *
 * `LOCAL` 是我们自己的目录（默认，行为与从前完全一致）；
 * 其余取值是某条元数据服务配置的 id，切过去就看那边的库表。
 */
const LOCAL = 'LOCAL';
const viewSourceId = ref<string | number>(LOCAL);
const viewSources = ref<MetadataSource[]>([]);
const viewSourcesLoading = ref(false);

const isLocalView = computed(() => viewSourceId.value === LOCAL);
const headerSubtitle = computed(() =>
  isLocalView.value
    ? '我们自己维护的表结构，供 SQL 解析时查列'
    : `来自「${viewSource.value?.name ?? ''}」的库表结构，只读；勾选后可导入到本地`
);


const viewSource = computed(() =>
  viewSources.value.find((s) => s.id === viewSourceId.value) ?? null
);

const viewOptions = computed(() => [
  { value: LOCAL, label: '本地元数据' },
  ...viewSources.value.map((s) => ({ value: s.id, label: s.name })),
]);

/**
 * 可切换的数据服务。
 *
 * 滤掉 CATALOG 类型 —— 那一条就是本地目录自己，列出来会让人以为有两个「本地」。
 */
async function loadViewSources() {
  viewSourcesLoading.value = true;
  try {
    viewSources.value = (await listMetadataSources()).filter((s) => s.type !== 'CATALOG');
  } catch {
    // 拉不到就只剩「本地元数据」一项，页面照常可用，不打扰用户
    viewSources.value = [];
  } finally {
    viewSourcesLoading.value = false;
  }
}

const catalogs = ref<string[]>([]);
const catalogsLoading = ref(false);
const tables = ref<MetaTable[]>([]);
const columns = ref<MetaColumn[]>([]);
const selected = ref<MetaTable | null>(null);
const schemas = ref<string[]>([]);
const loading = ref(false);
const columnsLoading = ref(false);
const total = ref(0);

const filters = reactive({
  // undefined = 全部目录；'' = 只看不属于任何目录的表
  catalog: undefined as string | undefined,
  schema: undefined as string | undefined,
  keyword: '',
});
const page = reactive({ current: 1, size: 20 });

const catalogOptions = computed(() => catalogs.value.map((c) => ({ value: c, label: c })));
const schemaOptions = computed(() => schemas.value.map((s) => ({ value: s, label: s })));

/** 换数据目录时把库的选择一起清掉，并回到第一页。 */
function onCatalogChange() {
  filters.schema = undefined;
  page.current = 1;
  reload();
}

async function loadCatalogs() {
  catalogsLoading.value = true;
  try {
    catalogs.value = await listMetaCatalogs();
  } catch (e: any) {
    message.error('加载数据目录失败：' + (e?.message || e));
  } finally {
    catalogsLoading.value = false;
  }
}

const pagination = computed(() => ({
  current: page.current,
  pageSize: page.size,
  total: total.value,
  size: 'small' as const,
  showSizeChanger: false,
  showTotal: (t: number) => `共 ${t} 张表`,
}));

const rowEvents = (record: MetaTable) => ({ onClick: () => select(record) });
const rowClass = (record: MetaTable) =>
  selected.value?.id === record.id ? 'row-selected' : '';

async function reload() {
  loading.value = true;
  try {
    const res = await listMetaTables({
      catalog: filters.catalog,
      schema: filters.schema,
      keyword: filters.keyword || undefined,
      page: page.current,
      size: page.size,
    });
    tables.value = res.items;
    total.value = res.total;
    // 当前选中的表可能已被筛掉或删除，避免右侧停留在一张看不见的表上
    if (selected.value && !res.items.some((t) => t.id === selected.value?.id)) {
      selected.value = null;
      columns.value = [];
    }
    // 默认选中第一张表：右侧那半屏空着只写「在左侧选择一张表」很浪费，
    // 大多数情况下用户进来就是想看看有哪些字段
    if (!selected.value && res.items.length) {
      await select(res.items[0]);
    }
  } catch (e: any) {
    message.error('加载元数据失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

function onTableChange(p: any) {
  page.current = p.current;
  reload();
}

async function select(record: MetaTable) {
  selected.value = record;
  columnsLoading.value = true;
  try {
    columns.value = await listMetaColumns(record.id);
  } catch (e: any) {
    message.error('加载字段失败：' + (e?.message || e));
    columns.value = [];
  } finally {
    columnsLoading.value = false;
  }
}

async function loadSchemas() {
  try {
    schemas.value = await listMetaSchemas();
  } catch {
    schemas.value = [];
  }
}

async function removeTable(record: MetaTable) {
  try {
    await deleteMetaTable(record.id);
    message.success(`已删除「${short(record.fullName)}」`);
    if (selected.value?.id === record.id) {
      selected.value = null;
      columns.value = [];
    }
    await Promise.all([reload(), loadSchemas(), loadCatalogs()]);
  } catch (e: any) {
    message.error('删除失败：' + (e?.message || e));
  }
}

// ---------------- 一、贴建表语句导入 ----------------

const ddlOpen = ref(false);
const ddlSubmitting = ref(false);
const ddlForm = reactive({ dbType: 'hive', ddl: '', catalogName: '', overwriteManual: false });

/** 配置页里维护的数据目录。与页面顶部那个筛选框不同 —— 那个来自 meta_table 里实际出现过的目录。 */
const dataCatalogs = ref<DataCatalog[]>([]);
const dataCatalogsLoading = ref(false);
const dataCatalogOptions = computed(() =>
  dataCatalogs.value.map((c) => ({
    value: c.name,
    label: c.isDefault ? `${c.name}（默认）` : c.name,
  }))
);

async function loadDataCatalogs() {
  dataCatalogsLoading.value = true;
  try {
    dataCatalogs.value = await listDataCatalogs();
  } catch (e: any) {
    message.error('加载数据目录失败：' + (e?.message || e));
  } finally {
    dataCatalogsLoading.value = false;
  }
}
const dbTypeOptions = ref<{ value: string; label: string }[]>([]);

async function openDdlImport() {
  ddlForm.ddl = '';
  ddlForm.overwriteManual = false;
  ddlOpen.value = true;
  if (!dataCatalogs.value.length) {
    await loadDataCatalogs();
  }
  // 每次打开都回到默认目录：上次选了别的目录不该悄悄延续到这次
  ddlForm.catalogName = dataCatalogs.value.find((c) => c.isDefault)?.name ?? '';
}

async function submitDdl() {
  if (!ddlForm.ddl.trim()) {
    message.error('请粘贴建表语句');
    return;
  }
  ddlSubmitting.value = true;
  try {
    reportResult(await importMetaDdl({ ...ddlForm, catalogName: ddlForm.catalogName || undefined }));
    ddlOpen.value = false;
    await Promise.all([reload(), loadSchemas(), loadCatalogs()]);
  } catch (e: any) {
    message.error('导入失败：' + (e?.message || e));
  } finally {
    ddlSubmitting.value = false;
  }
}

// ---------------- 二、从元数据服务同步 ----------------

const syncOpen = ref(false);
const syncSubmitting = ref(false);
const sources = ref<MetadataSource[]>([]);
const tableChoices = ref<string[]>([]);

const syncForm = reactive({
  sourceId: undefined as number | undefined,
  connectionId: undefined as string | undefined,
  metalake: undefined as string | undefined,
  catalog: undefined as string | undefined,
  database: undefined as string | undefined,
  schema: undefined as string | undefined,
  // CATALOG 整个数据目录 / SCHEMA 整个库 / TABLE 指定表
  scope: 'TABLE' as 'CATALOG' | 'SCHEMA' | 'TABLE',
  targetCatalog: undefined as string | string[] | undefined,
  tables: [] as string[],
  overwriteManual: false,
});

/** dbx 已保存的连接。有了它就不必再让用户手写 extraConfig.connectionId。 */
const connections = ref<DbxConnection[]>([]);
const connectionsLoading = ref(false);

/** 提交后拿到的任务，非空即表示弹窗切到进度视图。 */
const syncJob = ref<SyncJob | null>(null);
let syncPollTimer: number | undefined;

const isDbxSource = computed(() => currentSource.value?.type === 'DBX');

const connectionOptions = computed(() =>
  connections.value.map((c) => ({
    value: c.id,
    label: c.database ? `${c.name}（${c.dbType} · ${c.database}）` : `${c.name}（${c.dbType}）`,
  }))
);

/** Gravitino 叫 catalog，dbx 叫 database，页面上统一说「数据目录」。 */
const catalogLevelLabel = computed(() => (isDbxSource.value ? '数据库' : '数据目录'));

/** 「整个数据目录」这一级选没选。Gravitino 看 catalog，dbx 看 database。 */
const scopeRoot = computed(() =>
  isDbxSource.value ? syncForm.database : syncForm.catalog);

const sourceCatalogName = computed(() => scopeRoot.value ?? '');

/** 目标数据目录：已有的 + 源端那个，允许直接输入新建。 */
const targetCatalogOptions = computed(() => {
  const names = new Set<string>(catalogs.value);
  if (sourceCatalogName.value) names.add(sourceCatalogName.value);
  return Array.from(names).map((c) => ({ value: c, label: c }));
});

/** antd 的 tags 模式返回数组，这里取第一个当作单选值。 */
const targetCatalogValue = computed(() => {
  const v = syncForm.targetCatalog;
  if (Array.isArray(v)) return v.length ? v[v.length - 1] : undefined;
  return v || undefined;
});

const importPreview = computed(() => {
  const cat = targetCatalogValue.value || sourceCatalogName.value
    || catalogState.defaultCatalog || '默认目录';
  const schema = syncForm.schema || '<库>';
  if (syncForm.scope === 'CATALOG') return `将导入 ${cat} 下所有库的所有表`;
  if (syncForm.scope === 'SCHEMA') return `将导入 ${cat}.${schema} 下的所有表`;
  const n = syncForm.tables.length;
  return n ? `将导入为 ${cat}.${schema}.<表名>，共 ${n} 张` : '请在左侧勾选要导入的表';
});

const syncStatusLabel = computed(() => {
  const map: Record<string, string> = {
    PENDING: '排队中', RUNNING: '导入中', SUCCESS: '全部成功',
    PARTIAL: '部分失败', FAILED: '失败',
  };
  return map[syncJob.value?.status ?? ''] ?? '';
});

const levelOptions = reactive<Record<string, string[]>>({
  metalake: [], catalog: [], database: [], schema: [],
});
const levelLoading = reactive<Record<string, boolean>>({
  metalake: false, catalog: false, database: false, schema: false,
});

/** 本地目录不能作为同步来源 —— 它就是同步的目的地。 */
const syncSourceOptions = computed(() =>
  sources.value
    .filter((s) => s.type !== 'CATALOG' && s.enabled)
    .map((s) => ({ value: s.id, label: `${s.name}（${s.type}）` }))
);

const currentSource = computed(() =>
  sources.value.find((s) => s.id === syncForm.sourceId) ?? null
);

/**
 * 层级链：Gravitino 是 metalake → catalog → schema，dbx 是 database → schema。
 * 上一级没选时下一级禁用，避免用户跳着选出无意义的组合。
 */
const levels = computed(() => {
  const type = currentSource.value?.type;
  const chain = type === 'GRAVITINO'
    ? [
        { key: 'metalake', label: 'Metalake' },
        { key: 'catalog', label: 'Catalog' },
        { key: 'schema', label: 'Schema' },
      ]
    : [
        { key: 'database', label: '数据库' },
        { key: 'schema', label: 'Schema' },
      ];

  return chain.map((level, index) => ({
    ...level,
    options: levelOptions[level.key] ?? [],
    loading: levelLoading[level.key] ?? false,
    disabled: index > 0 && !(syncForm as any)[chain[index - 1].key],
  }));
});

const allTablesChecked = computed(
  () => tableChoices.value.length > 0 && syncForm.tables.length === tableChoices.value.length
);
const someTablesChecked = computed(
  () => syncForm.tables.length > 0 && !allTablesChecked.value
);

function toggleAllTables(e: any) {
  syncForm.tables = e.target.checked ? [...tableChoices.value] : [];
}

async function openSync() {
  stopPolling();
  syncJob.value = null;
  resetSync();
  syncOpen.value = true;
  try {
    sources.value = await listMetadataSources();
  } catch (e: any) {
    message.error('加载元数据服务失败：' + (e?.message || e));
  }
}

function resetSync() {
  syncForm.sourceId = undefined;
  syncForm.connectionId = undefined;
  syncForm.scope = 'TABLE';
  syncForm.targetCatalog = undefined;
  connections.value = [];
  syncForm.metalake = undefined;
  syncForm.catalog = undefined;
  syncForm.database = undefined;
  syncForm.schema = undefined;
  syncForm.tables = [];
  syncForm.overwriteManual = false;
  tableChoices.value = [];
  Object.keys(levelOptions).forEach((k) => (levelOptions[k] = []));
}

async function onSyncSourceChange() {
  syncForm.connectionId = undefined;
  syncForm.targetCatalog = undefined;
  syncForm.scope = 'TABLE';
  connections.value = [];
  syncForm.metalake = undefined;
  syncForm.catalog = undefined;
  syncForm.database = undefined;
  syncForm.schema = undefined;
  syncForm.tables = [];
  tableChoices.value = [];
  Object.keys(levelOptions).forEach((k) => (levelOptions[k] = []));

  if (isDbxSource.value) {
    // dbx 要先选连接才能列出数据库，所以这里不急着拉下一级
    await loadConnections();
    return;
  }
  await loadLevel('metalake');
}

async function loadConnections() {
  if (!syncForm.sourceId) return;
  connectionsLoading.value = true;
  try {
    connections.value = await listDbxConnections(syncForm.sourceId);
  } catch (e: any) {
    message.error('获取 dbx 连接列表失败：' + (e?.message || e));
  } finally {
    connectionsLoading.value = false;
  }
}

/** 换连接等于换了一整套库/表，下游全清掉。 */
async function onConnectionChange() {
  syncForm.database = undefined;
  syncForm.schema = undefined;
  syncForm.tables = [];
  tableChoices.value = [];
  Object.keys(levelOptions).forEach((k) => (levelOptions[k] = []));
  await loadLevel('database');
}

/** 范围变了要清掉不再适用的选择，避免提交出自相矛盾的组合。 */
function onScopeChange() {
  if (syncForm.scope !== 'TABLE') {
    syncForm.tables = [];
  }
}

function onTargetCatalogSearch() {
  // tags 模式下 antd 自己会把输入项加进选项，这里无需额外处理
}

/** 选了某一级之后，清空它下面所有级，再去拉下一级。 */
async function onLevelChange(key: string, value: string | undefined) {
  const chain = levels.value.map((l) => l.key);
  const index = chain.indexOf(key);
  (syncForm as any)[key] = value;

  chain.slice(index + 1).forEach((k) => {
    (syncForm as any)[k] = undefined;
    levelOptions[k] = [];
  });
  syncForm.tables = [];
  tableChoices.value = [];

  if (!value) {
    return;
  }
  const next = chain[index + 1];
  if (next) {
    await loadLevel(next);
  } else {
    // 已经是最后一级，接着拉表清单
    await loadTableChoices();
  }
}

async function loadLevel(key: string) {
  if (!syncForm.sourceId) return;
  levelLoading[key] = true;
  try {
    levelOptions[key] = await browseMetaSource({
      sourceId: syncForm.sourceId,
      connectionId: syncForm.connectionId,
      metalake: syncForm.metalake,
      catalog: syncForm.catalog,
      database: syncForm.database,
      // schema 这一级本身要靠不传 schema 才能拿到，所以只在拉表清单时才带上
      schema: undefined,
    });
  } catch (e: any) {
    levelOptions[key] = [];
    message.error('浏览失败：' + (e?.message || e));
  } finally {
    levelLoading[key] = false;
  }
}

async function loadTableChoices() {
  if (!syncForm.sourceId) return;
  try {
    tableChoices.value = await browseMetaSource({
      sourceId: syncForm.sourceId,
      connectionId: syncForm.connectionId,
      metalake: syncForm.metalake,
      catalog: syncForm.catalog,
      database: syncForm.database,
      schema: syncForm.schema,
    });
  } catch (e: any) {
    tableChoices.value = [];
    message.error('获取表清单失败：' + (e?.message || e));
  }
}

async function submitSync() {
  if (!syncForm.sourceId) {
    message.error('请选择元数据服务');
    return;
  }
  if (syncForm.scope === 'TABLE' && !syncForm.tables.length) {
    message.error('按表导入时请至少勾选一张表');
    return;
  }
  if (syncForm.scope === 'SCHEMA' && !syncForm.schema) {
    message.error('按库导入时请先选择库');
    return;
  }
  if (syncForm.scope === 'CATALOG' && !scopeRoot.value) {
    message.error(`按${catalogLevelLabel.value}导入时请先选择${catalogLevelLabel.value}`);
    return;
  }

  syncSubmitting.value = true;
  try {
    const { jobId } = await submitMetaSync({
      sourceId: syncForm.sourceId,
      scope: syncForm.scope,
      connectionId: syncForm.connectionId,
      metalake: syncForm.metalake,
      catalog: syncForm.catalog,
      database: syncForm.database,
      schema: syncForm.schema,
      targetCatalog: targetCatalogValue.value,
      tables: syncForm.tables,
      overwriteManual: syncForm.overwriteManual,
    });
    // 切到进度视图并开始轮询。导入是异步的，可能要跑很久
    startPolling(jobId);
  } catch (e: any) {
    message.error('提交导入任务失败：' + (e?.message || e));
  } finally {
    syncSubmitting.value = false;
  }
}

/**
 * 轮询任务进度。
 *
 * <p>1 秒一次：再快对上万张表的导入也没意义，还会把自己的接口打满。
 * 任务结束就停轮询并刷新左侧列表。
 */
function startPolling(jobId: number) {
  stopPolling();
  const tick = async () => {
    try {
      const job = await getMetaSyncJob(jobId);
      syncJob.value = job;
      if (job.finished) {
        stopPolling();
        await Promise.all([reload(), loadSchemas(), loadCatalogs()]);
      }
    } catch (e: any) {
      stopPolling();
      message.error('查询导入进度失败：' + (e?.message || e));
    }
  };
  tick();
  syncPollTimer = window.setInterval(tick, 1000);
}

function stopPolling() {
  if (syncPollTimer !== undefined) {
    window.clearInterval(syncPollTimer);
    syncPollTimer = undefined;
  }
}

function closeSync() {
  stopPolling();
  syncOpen.value = false;
  syncJob.value = null;
}

/** 导入与同步共用：把「跳过」和「失败」说清楚，别只报一个成功数。 */
function reportResult(result: MetaWriteResult) {
  const parts = [`新增 ${result.created}`, `更新 ${result.updated}`];
  if (result.skipped) parts.push(`跳过 ${result.skipped}`);
  if (result.failed) parts.push(`失败 ${result.failed}`);
  const summary = parts.join(' / ');

  if (result.failed) {
    message.warning(`${summary}。失败：${result.failures.map((f) => f.table).join('、')}`);
  } else if (result.skipped) {
    message.warning(`${summary}。以下表因人工维护被跳过：${result.skippedTables.join('、')}`);
  } else {
    message.success(summary);
  }
}

// ---------------- 三、手工修改 ----------------

const tableEditOpen = ref(false);
const savingTable = ref(false);
const tableEditForm = reactive({
  id: 0, fullName: '', tableType: undefined as string | undefined,
  comment: '', remark: '',
});

function openEditTable(record: MetaTable) {
  tableEditForm.id = record.id;
  tableEditForm.fullName = record.fullName;
  tableEditForm.tableType = record.tableType || undefined;
  tableEditForm.comment = record.comment || '';
  tableEditForm.remark = record.remark || '';
  tableEditOpen.value = true;
}

async function saveTable() {
  savingTable.value = true;
  try {
    await updateMetaTable(tableEditForm.id, {
      tableType: tableEditForm.tableType,
      comment: tableEditForm.comment,
      remark: tableEditForm.remark,
    });
    message.success('已保存');
    tableEditOpen.value = false;
    await reload();
    if (selected.value?.id === tableEditForm.id) {
      const refreshed = tables.value.find((t) => t.id === tableEditForm.id);
      if (refreshed) selected.value = refreshed;
    }
  } catch (e: any) {
    message.error('保存失败：' + (e?.message || e));
  } finally {
    savingTable.value = false;
  }
}

const columnEditOpen = ref(false);
const savingColumn = ref(false);
const columnEditForm = reactive({
  id: 0, columnName: '', dataType: '', comment: '', remark: '',
});

function openEditColumn(record: MetaColumn) {
  columnEditForm.id = record.id;
  columnEditForm.columnName = record.columnName;
  columnEditForm.dataType = record.dataType || '';
  columnEditForm.comment = record.comment || '';
  columnEditForm.remark = record.remark || '';
  columnEditOpen.value = true;
}

async function saveColumn() {
  savingColumn.value = true;
  try {
    await updateMetaColumn(columnEditForm.id, {
      dataType: columnEditForm.dataType,
      comment: columnEditForm.comment,
      remark: columnEditForm.remark,
    });
    message.success('已保存');
    columnEditOpen.value = false;
    if (selected.value) await select(selected.value);
  } catch (e: any) {
    message.error('保存失败：' + (e?.message || e));
  } finally {
    savingColumn.value = false;
  }
}

// ---------------- 杂项 ----------------

const SOURCE_LABELS: Record<MetaSource, string> = {
  DDL: '建表语句',
  GRAVITINO: 'Gravitino',
  DBX: 'dbx',
  MANUAL: '手工维护',
};

const SOURCE_COLORS: Record<MetaSource, string> = {
  DDL: 'geekblue',
  GRAVITINO: 'blue',
  DBX: 'orange',
  MANUAL: 'green',
};

const sourceLabel = (s: MetaSource) => SOURCE_LABELS[s] ?? s;
const sourceColor = (s: MetaSource) => SOURCE_COLORS[s] ?? 'default';

function formatTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '';
}

onUnmounted(stopPolling);

onMounted(async () => {
  await Promise.all([reload(), loadSchemas(), loadCatalogs(), loadViewSources()]);
  try {
    dbTypeOptions.value = (await getDbTypes()).map((t) => ({ value: t, label: t }));
  } catch {
    dbTypeOptions.value = [{ value: 'hive', label: 'hive' }];
  }
});
</script>

<style scoped>
.meta-page {
  height: 100%;
  overflow: auto;
}

.page-body {
  max-width: 1600px;
  margin: 0 auto;
  padding: 16px 20px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.toolbar-filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

/* 传送进来的元素要直接参与这一行的 flex 布局，不能自成一个盒子 */
.teleport-inline {
  display: contents;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.filter-schema {
  width: 160px;
}

.filter-keyword {
  width: 260px;
}

.split {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.pane-tables {
  flex: 0 0 460px;
  background: #fff;
  border-radius: 6px;
  padding: 8px;
}

.pane-columns {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 6px;
  padding: 12px;
  min-height: 360px;
}

.table-name {
  font-weight: 500;
  color: #1f2937;
}

.table-sub {
  font-size: 12px;
  color: #6b7280;
}

.detail-head {
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid #f0f0f0;
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.detail-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
  display: flex;
  gap: 16px;
}

.detail-synced {
  color: #9ca3af;
}

.empty-hint {
  color: #9ca3af;
  text-align: center;
  padding: 120px 0;
}

.muted {
  color: #d1d5db;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.6;
}

.level-row {
  display: flex;
  gap: 12px;
}

.level-item {
  flex: 1;
}

.table-picker {
  max-height: 240px;
  overflow: auto;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 8px 10px;
}

.table-checkboxes {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}

.mb-3 {
  margin-bottom: 12px;
}

.mt-3 {
  margin-top: 12px;
}

.ml-2 {
  margin-left: 8px;
}

:deep(.row-selected) > td {
  background: #e6f4ff !important;
}

:deep(.ant-table-row) {
  cursor: pointer;
}

.table-catalog {
  color: #1677ff;
}

/* 导入弹窗：左右两栏 */
.sync-panes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.sync-pane {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px 16px;
}

.sync-pane-title {
  font-weight: 500;
  color: #1f2937;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.sync-preview {
  background: #fafafa;
  border-radius: 4px;
  padding: 8px 10px;
  color: #4b5563;
  font-size: 13px;
  word-break: break-all;
}

.sync-progress {
  padding: 8px 4px;
}

.sync-progress-line {
  margin-top: 8px;
  color: #4b5563;
}

.sync-status-tag {
  margin-left: 10px;
  color: #1677ff;
}

.sync-counts {
  margin-top: 10px;
}

.sync-failures {
  margin-top: 14px;
  max-height: 220px;
  overflow: auto;
  background: #fff7f6;
  border: 1px solid #ffccc7;
  border-radius: 4px;
  padding: 8px 10px;
}

.sync-failures-title {
  color: #cf1322;
  margin-bottom: 6px;
}

.sync-failure-item {
  font-size: 12px;
  color: #7a1f1a;
  padding: 2px 0;
  word-break: break-all;
}
</style>
