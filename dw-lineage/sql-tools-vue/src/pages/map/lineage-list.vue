<template>
  <div class="page">
    <PageHeader title="血缘" subtitle="解析并保存过血缘的表">
      <template #actions>
        <Button :loading="loading" @click="load">刷新</Button>
      </template>
      <template #help>
        <p>
          这里列的是<b>血缘侧</b>的表 —— 在「数据地图 › SQL 解析」页解析并保存过的表，以及它们
          在血缘中引用到的上游表。一张表出现在这里，说明库里有它的血缘边。
        </p>
        <p>
          与「元数据」不是一回事：那边是数据库里真实存在的表结构，供解析时查列；
          这边是从 SQL 推导出来的血缘。两者<b>分开存储</b>，只在展示时按表全名关联，
          所以这里可能有元数据里没有的表（<code>元数据缺失</code>），反之亦然。
        </p>
        <p>
          点任意一行进详情，那里有字段、血缘图和历史版本。
        </p>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <span class="filter-label">数据目录</span>
      <Select v-model:value="filters.catalog" class="w-160" placeholder="全部目录"
              show-search allow-clear :options="catalogOptions" />

      <span class="filter-label">库名</span>
      <Select v-model:value="filters.schema" class="w-160" placeholder="全部库"
              show-search allow-clear :options="schemaOptions" />

      <span class="filter-label">表类型</span>
      <Select v-model:value="filters.tableType" class="w-140" placeholder="全部类型"
              allow-clear :options="tableTypeOptions" />

      <Input v-model:value="filters.keyword" class="w-260" allow-clear
             placeholder="搜索表名或中文名" />

      <Button @click="reset">重置</Button>
      <span class="muted filter-count">共 {{ filtered.length }} 张表</span>
    </div>

    <div class="card card-flush">
      <Table
        :columns="columnDefs"
        :data-source="filtered"
        :loading="loading"
        row-key="id"
        size="small"
        :pagination="pagination"
        :custom-row="rowEvents"
        class="clickable-rows"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <div class="t-name">{{ record.tableName }}</div>
            <div class="t-sub">
              <span v-if="showCatalog(record as CatalogTable)" class="t-catalog">{{ record.catalogName }} / </span>
              {{ record.schemaName }}<span v-if="record.comment"> · {{ record.comment }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'tableType'">
            <Tag v-if="record.tableType" color="blue">{{ tableTypeLabel(record.tableType) }}</Tag>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'dbType'">
            <span v-if="record.dbType">{{ record.dbType }}</span>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'meta'">
            <span v-if="record.metaTableId" class="ok">已关联</span>
            <Tooltip v-else title="元数据目录里没有这张表的结构，select * 之类的解析会缺列">
              <span class="muted">缺失</span>
            </Tooltip>
          </template>
          <template v-else-if="column.key === 'action'">
            <Button type="link" size="small" @click.stop="open(record.id)">详情</Button>
            <Button type="link" size="small" @click.stop="open(record.id, 'lineage')">血缘图</Button>
          </template>
        </template>
      </Table>

      <div v-if="!loading && !tables.length" class="page-empty">
        <div class="page-empty-text">还没有任何血缘数据</div>
        <Button type="primary" class="bg-[#1677ff]" @click="router.push('/lineage')">去解析一段 SQL</Button>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Button, Input, Select, Table, Tag, Tooltip, message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import { tableTypeLabel, tableTypeOptions } from '../../config/tableTypes';
import { listCatalogTables } from '../../services/api';
import type { CatalogTable } from '../../services/api';
import { catalogState } from '../../stores/catalog';

/**
 * 血缘表列表。
 *
 * 改版前没有这个页面：要看一张表，得先在「数据目录 → 库 → 表」三个下拉里
 * 级联选到底，不知道表名就寸步难行。接口本来就支持不带参数列全部
 * （`GET /api/catalog/tables` 的 catalog / schema 都是可选），前端只是一直没这么用。
 *
 * 筛选全在前端做：一次取回全部，翻页和改条件都不再回后端，
 * 也就不会出现「翻到第二页时数据已经变了」。
 */
const route = useRoute();
const router = useRouter();

const columnDefs = [
  { title: '表名', key: 'name' },
  { title: '表类型', key: 'tableType', width: 120 },
  { title: '方言', key: 'dbType', width: 100 },
  { title: '元数据', key: 'meta', width: 90 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' as const },
];

const tables = ref<CatalogTable[]>([]);
const loading = ref(false);
const filters = reactive({
  catalog: undefined as string | undefined,
  schema: undefined as string | undefined,
  tableType: undefined as string | undefined,
  keyword: '',
});

/** 下拉选项从已取回的数据里推，不用再单独请求两个列表接口。 */
const catalogOptions = computed(() =>
  uniq(tables.value.map((t) => t.catalogName).filter(Boolean) as string[])
    .map((c) => ({ value: c, label: c }))
);
const schemaOptions = computed(() =>
  uniq(
    tables.value
      .filter((t) => !filters.catalog || t.catalogName === filters.catalog)
      .map((t) => t.schemaName)
  ).map((s) => ({ value: s, label: s }))
);

const filtered = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return tables.value.filter((t) => {
    if (filters.catalog && t.catalogName !== filters.catalog) return false;
    if (filters.schema && t.schemaName !== filters.schema) return false;
    if (filters.tableType && t.tableType !== filters.tableType) return false;
    if (keyword) {
      const hay = `${t.tableName} ${t.comment ?? ''}`.toLowerCase();
      if (!hay.includes(keyword)) return false;
    }
    return true;
  });
});

const pagination = computed(() => ({
  pageSize: 20,
  size: 'small' as const,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (t: number) => `共 ${t} 条`,
}));

/** 默认目录下的表不显示目录前缀，那一段对每一行都一样，纯噪音。 */
const showCatalog = (row: CatalogTable) =>
  !!row.catalogName && row.catalogName !== catalogState.defaultCatalog;

const rowEvents = (record: CatalogTable) => ({ onClick: () => open(record.id) });

function open(id: number, tab?: string) {
  router.push({ path: `/lineage/tables/${id}`, query: tab ? { tab } : undefined });
}

function uniq(values: string[]): string[] {
  return Array.from(new Set(values)).sort();
}

function reset() {
  filters.catalog = undefined;
  filters.schema = undefined;
  filters.tableType = undefined;
  filters.keyword = '';
}

async function load() {
  loading.value = true;
  try {
    tables.value = await listCatalogTables();
  } catch (e: any) {
    message.error('加载表列表失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

/**
 * 旧链接落地。
 *
 * `/lineage/graph?start=库.表` 和 `/catalog/tables?schema=&table=` 都被路由重定向到
 * 这里，带着原来的 query。它们按名字定位，而详情页按 id 组织，所以在这里换算一次
 * 再转过去。名字对不上就留在列表页 —— 总比跳到一个空详情强。
 */
function applyRouteQuery() {
  const start = route.query.start as string | undefined;
  const schema = route.query.schema as string | undefined;
  const tableName = route.query.table as string | undefined;

  let hit: CatalogTable | undefined;
  if (start) {
    // start 可能是两段（库.表）也可能是三段（目录.库.表）
    hit = tables.value.find((t) => t.fullName === start)
      ?? tables.value.find((t) => `${t.schemaName}.${t.tableName}` === start);
  } else if (schema && tableName) {
    hit = tables.value.find((t) => t.schemaName === schema && t.tableName === tableName);
  } else {
    return;
  }

  if (hit) {
    router.replace({
      path: `/lineage/tables/${hit.id}`,
      query: start ? { tab: 'lineage' } : undefined,
    });
  } else {
    message.warning(`没找到表「${start || `${schema}.${tableName}`}」，已停在列表页`);
  }
}

onMounted(async () => {
  await load();
  applyRouteQuery();
});
</script>

<style scoped>
.w-140 { width: 140px; }
.w-160 { width: 160px; }
.w-260 { width: 260px; }

.filter-count {
  margin-left: auto;
  font-size: 13px;
}

.t-name {
  color: #1f2937;
}

.t-sub {
  font-size: 12px;
  color: #9ca3af;
}

.t-catalog {
  color: #6b7280;
}

.ok {
  color: #16a34a;
}

.clickable-rows :deep(.ant-table-row) {
  cursor: pointer;
}
</style>
