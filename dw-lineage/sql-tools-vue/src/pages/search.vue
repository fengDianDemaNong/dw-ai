<template>
  <ConfigProvider :wave="{ disabled: true }">
    <div class="search-page">
      <main class="page-body">
        <PageHeader title="全文检索" subtitle="在表名、中文名、备注、字段名里找">
          <template #help>
            <p>
              多个条件用<b>空格</b>分隔，词间是「且」的关系；每个词会在表名、表中文名、
              备注、字段名、字段中文名里分别找，命中任意一处即算这个词匹配上。
            </p>
            <p>大小写不敏感。后端一次返回全部命中（有条数上限），翻页不再回后端。</p>
            <p>点结果行的「查看」可以直接进那张表的详情。</p>
          </template>
        </PageHeader>

        <div class="filter-bar">
          <span class="filter-label">表类型</span>
          <Select
            v-model:value="form.tableType"
            class="filter-type"
            placeholder="全部"
            allow-clear
            :options="tableTypeOptions"
          />
          <span class="filter-label">查询条件</span>
          <Input
            ref="keywordRef"
            v-model:value="form.keyword"
            class="filter-keyword"
            placeholder="可搜索：表名、表中文名、备注、字段名、字段中文名。多个条件用空格分隔"
            allow-clear
            @press-enter="doSearch"
          />
          <Button type="primary" class="bg-[#1677ff]" :loading="loading" @click="doSearch">搜索</Button>
          <Button @click="reset">重置</Button>
          <Tooltip title="多个条件之间是「且」的关系，每个条件会在上面列出的所有字段里找">
            <span class="hint-icon">?</span>
          </Tooltip>
        </div>

        <!-- 还没搜过就别摆一张空表格：从菜单进来时是没有关键字的 -->
        <div v-if="!searched && !loading" class="page-empty">
          <div class="page-empty-text">
            输入关键字后回车。多个条件用空格分隔，词间是「且」的关系
          </div>
        </div>

        <div v-else class="card card-flush">
        <Table
          :columns="columnDefs"
          :data-source="rows"
          :loading="loading"
          row-key="rowKey"
          size="small"
          :pagination="pagination"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'tableType'">
              <span v-if="record.tableType">{{ tableTypeLabel(record.tableType) }}</span>
              <span v-else class="muted">—</span>
            </template>
            <template v-else-if="column.key === 'columnName'">
              <span v-if="record.columnName">{{ record.columnName }}</span>
              <span v-else class="muted">—</span>
            </template>
            <template v-else-if="column.key === 'columnComment'">
              <span v-if="record.columnName">{{ record.columnComment || '—' }}</span>
              <span v-else class="muted">命中表信息</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <Button type="link" size="small" @click="view(record as SearchRow)">查看</Button>
            </template>
            <template v-else-if="column.key === 'tableComment'">
              <span v-if="record.tableComment">{{ record.tableComment }}</span>
              <span v-else class="muted">—</span>
            </template>
          </template>
        </Table>

        <Empty v-if="!rows.length && !loading" description="没有匹配的表或字段" class="empty" />
        </div>
      </main>
    </div>
  </ConfigProvider>
</template>

<script lang="ts" setup>
import PageHeader from '../components/PageHeader/index.vue';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Button, ConfigProvider, Empty, Input, Select, Table, Tooltip, message } from 'ant-design-vue';
import { tableTypeOptions, tableTypeLabel } from '../config/tableTypes';
import { searchCatalog } from '../services/api';
import type { CatalogSearchHit } from '../services/api';

const router = useRouter();

type SearchRow = CatalogSearchHit & { rowKey: string };

const columnDefs = [
  // 放在库前面：两个数据目录下可能有同名的 schema.table，不显示目录就分不清命中的是哪一张
  {
    title: '数据目录',
    dataIndex: 'catalogName',
    key: 'catalogName',
    width: 130,
    customRender: ({ text }: { text?: string }) => text || '—',
  },
  { title: '库', dataIndex: 'schemaName', key: 'schemaName', width: 120 },
  { title: '表名', dataIndex: 'tableName', key: 'tableName', width: 200 },
  { title: '表类型', key: 'tableType', width: 110 },
  { title: '表中文名', key: 'tableComment', width: 180 },
  { title: '字段名', key: 'columnName', width: 180 },
  { title: '字段中文名', key: 'columnComment' },
  { title: '操作', key: 'action', width: 80, fixed: 'right' as const },
];

const route = useRoute();

// 带 ?keyword= 进来时（比如别处分享的链接）直接接上继续查，不要让用户再打一遍
const form = reactive({
  keyword: (route.query.keyword as string) || '',
  tableType: undefined as string | undefined,
});
const rows = ref<SearchRow[]>([]);
const loading = ref(false);
const searched = ref(false);

/**
 * 前端分页。
 *
 * 后端一次返回全部命中（有上限），这里不再回后端翻页 —— 搜索结果量级有限，
 * 而且翻页时重查会让「多词 AND」的结果在两页之间不一致。
 */
const pagination = computed(() => ({
  pageSize: 20,
  size: 'small' as const,
  showSizeChanger: false,
  showTotal: (t: number) => `共 ${t} 条`,
}));

async function doSearch() {
  if (!form.keyword.trim() && !form.tableType) {
    message.warning('请输入查询条件');
    return;
  }
  loading.value = true;
  try {
    const hits = await searchCatalog(form.keyword.trim(), form.tableType);
    // 同一张表的多个字段命中会产生多行，这里给稳定的 rowKey
    rows.value = hits.map((h, i) => ({ ...h, rowKey: `${h.fullName}-${h.columnId ?? 'table'}-${i}` }));
    searched.value = true;
  } catch (e: any) {
    message.error('搜索失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

const keywordRef = ref();

onMounted(() => {
  if (form.keyword.trim()) {
    void doSearch();
    return;
  }
  // 从菜单进来时没有关键字，光标直接落进输入框，省一次点击
  keywordRef.value?.focus();
});

function reset() {
  form.keyword = '';
  form.tableType = undefined;
  rows.value = [];
  searched.value = false;
}

/** 命中里带着 tableId，直接进详情，不用再按名字重查一遍。 */
function view(row: SearchRow) {
  router.push(`/lineage/tables/${row.tableId}`);
}
</script>

<style scoped>
.search-page {
  height: 100%;
  overflow: auto;
}

.page-body {
  max-width: 1600px;
  margin: 0 auto;
  padding: 16px 20px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-label {
  color: #4b5563;
  font-size: 14px;
}

.filter-type {
  width: 150px;
}

.filter-keyword {
  width: 520px;
}

.hint-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid #d1d5db;
  color: #6b7280;
  font-size: 12px;
  cursor: help;
}

.muted {
  color: #c0c4cc;
}

.empty {
  margin-top: 80px;
}
</style>
