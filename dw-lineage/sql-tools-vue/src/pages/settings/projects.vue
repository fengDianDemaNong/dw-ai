<template>
  <div class="page">
    <PageHeader title="项目" :subtitle="subtitle">
      <template #actions>
        <Button :loading="loading" @click="load">刷新</Button>
        <Button type="primary" class="bg-[#1677ff]" :disabled="!tenants.length"
                @click="openModal()">新增项目</Button>
      </template>
      <template #help>
        <p>
          项目是租户下面的第二级隔离。血缘、元数据、元数据服务配置都挂在
          <b>租户 + 项目</b>这一对上，换一个项目看到的就是另一份数据。
        </p>
        <p>
          每个租户至少有一个项目：新建租户时会自动生成一个默认项目，
          并为它配好内置的「本地元数据目录」。
        </p>
        <p>
          删除只在其下没有任何业务数据时才允许，各租户的默认项目删不掉。
        </p>
        <p>
          这里改的是「有哪些项目」；要切换<b>自己正在用的</b>那个，用右上角的
          项目切换。改租户仍在「设置 › 基本信息」。
        </p>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <span class="filter-label">租户</span>
      <Select
        v-model:value="filterTenantId"
        class="w-240"
        placeholder="全部租户"
        show-search
        allow-clear
        option-filter-prop="label"
        :options="tenantOptions"
        :loading="loading"
        @change="syncQuery"
      />
      <Button @click="reset">重置</Button>
      <span class="muted count">共 {{ rows.length }} 个项目</span>
    </div>

    <div class="card card-flush">
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span>{{ record.name }}</span>
            <Tag v-if="isCurrent(record as ProjectRow)" color="blue" class="ml-2">当前</Tag>
            <Tag v-if="record.id === DEFAULT_PROJECT_ID" class="ml-2">默认</Tag>
          </template>
          <template v-else-if="column.key === 'tenant'">
            <a @click="filterBy(record.tenantId)">{{ record.tenantName }}</a>
          </template>
          <template v-else-if="column.key === 'description'">
            <span v-if="record.description">{{ record.description }}</span>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="record.enabled ? 'green' : 'default'">
              {{ record.enabled ? '启用' : '停用' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a @click="openModal(record as ProjectRow)">编辑</a>
            <a class="ml-2" @click="toggle(record as ProjectRow)">
              {{ record.enabled ? '停用' : '启用' }}
            </a>
            <Popconfirm
              title="确定删除该项目？仅在其下没有任何业务数据时可删。"
              @confirm="remove(record as ProjectRow)"
            >
              <!-- 默认项目后端拒删，这里同步置灰，别让用户点了才知道 -->
              <a class="ml-2 danger" :class="{ disabled: record.id === DEFAULT_PROJECT_ID }">删除</a>
            </Popconfirm>
          </template>
        </template>
      </Table>

      <div v-if="!loading && !rows.length" class="page-empty">
        <div class="page-empty-text">
          {{ filterTenantId ? '这个租户下还没有项目' : '还没有任何项目' }}
        </div>
        <Button v-if="tenants.length" type="primary" class="bg-[#1677ff]" @click="openModal()">
          新增项目
        </Button>
      </div>
    </div>

    <Modal v-model:open="modal.open" :title="modal.id ? '编辑项目' : '新增项目'"
           :confirm-loading="saving" @ok="submit">
      <Form layout="vertical" class="mt-3">
        <!-- 归属租户放第一个：项目不能脱离租户存在，先定归属再填别的。
             编辑时不给改 —— 换租户等于把项目连同它底下的血缘一起搬家，后端没有这个语义 -->
        <FormItem label="所属租户" :help="modal.id ? '归属创建后不可修改' : undefined">
          <Select v-model:value="modal.tenantId" :disabled="!!modal.id"
                  show-search option-filter-prop="label"
                  placeholder="选择租户" :options="tenantOptions" />
        </FormItem>
        <FormItem label="项目编码" :help="modal.id ? '编码创建后不可修改' : CODE_HELP">
          <Input v-model:value="modal.code" :disabled="!!modal.id" placeholder="如 dw" />
        </FormItem>
        <FormItem label="项目名称">
          <Input v-model:value="modal.name" placeholder="如 离线数仓" />
        </FormItem>
        <FormItem label="描述">
          <Textarea v-model:value="modal.description" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Button, Form, FormItem, Input, Modal, Popconfirm, Select, Table, Tag, Textarea, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import {
  createProject, deleteProject, listTenants, updateProject,
  type Project, type Tenant,
} from '../../services/api';
import { DEFAULT_PROJECT_ID, tenantState } from '../../stores/tenant';

/**
 * 项目管理。
 *
 * 从原先的「租户与项目」右半栏拆出来。那时项目列表跟着左表的选中行走，
 * 想看某个租户的项目必须先点中它，也没法一眼看到全部项目。
 *
 * 数据仍来自 `listTenants()` —— 它本来就把每个租户的 projects 一起带回来了，
 * 不需要为这一页加接口。租户名在表格里就地显示，省掉一次按 id 反查。
 */
const CODE_HELP = '只能用小写字母、数字、下划线和短横线，创建后不可修改';

type ProjectRow = Project & { tenantName: string };

const route = useRoute();
const router = useRouter();

const columns = [
  { title: '项目', dataIndex: 'name', key: 'name' },
  { title: '编码', dataIndex: 'code', key: 'code', width: 140 },
  { title: '所属租户', key: 'tenant', width: 180 },
  { title: '描述', key: 'description', ellipsis: true },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 170 },
];

const tenants = ref<Tenant[]>([]);
const loading = ref(false);
const saving = ref(false);

/** 筛选的租户；undefined = 全部。与地址栏的 ?tenantId= 双向同步，链接可分享 */
const filterTenantId = ref<number | undefined>(readTenantIdFromQuery());

const modal = reactive({
  open: false,
  id: 0,
  tenantId: undefined as number | undefined,
  code: '',
  name: '',
  description: '',
});

const tenantOptions = computed(() =>
  tenants.value.map((t) => ({
    value: t.id,
    // 停用的租户列出来但标注：藏起来的话，正停在上面的项目会显得没有归属
    label: t.enabled ? t.name : `${t.name}（已停用）`,
  }))
);

/** 打平成行，顺带把租户名带上，表格里不用再按 id 反查。 */
const allRows = computed<ProjectRow[]>(() =>
  tenants.value.flatMap((t) => t.projects.map((p) => ({ ...p, tenantName: t.name })))
);

const rows = computed(() =>
  filterTenantId.value
    ? allRows.value.filter((p) => p.tenantId === filterTenantId.value)
    : allRows.value
);

const subtitle = computed(() => {
  const hit = tenants.value.find((t) => t.id === filterTenantId.value);
  return hit ? `租户「${hit.name}」下的项目` : '租户下面的第二级隔离';
});

const isCurrent = (row: ProjectRow) =>
  row.id === tenantState.projectId && row.tenantId === tenantState.tenantId;

function readTenantIdFromQuery(): number | undefined {
  const raw = Number(route.query.tenantId);
  return Number.isFinite(raw) && raw > 0 ? raw : undefined;
}

/** 筛选值写回地址栏，刷新和分享链接都能保住当前视图。 */
function syncQuery() {
  router.replace({
    path: '/lineage/settings/projects',
    query: filterTenantId.value ? { tenantId: String(filterTenantId.value) } : {},
  });
}

function filterBy(tenantId: number) {
  filterTenantId.value = tenantId;
  syncQuery();
}

function reset() {
  filterTenantId.value = undefined;
  syncQuery();
}

// 从租户页点过来时地址栏变了但组件不重建，要跟着更新筛选
watch(() => route.query.tenantId, () => (filterTenantId.value = readTenantIdFromQuery()));

async function load(): Promise<void> {
  loading.value = true;
  try {
    tenants.value = await listTenants();
  } catch (e: any) {
    message.error('加载项目失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

function openModal(record?: ProjectRow): void {
  modal.open = true;
  modal.id = record?.id ?? 0;
  // 新建时预填当前筛选的租户；没筛选就预填正在使用的那个，省一次选择
  modal.tenantId = record?.tenantId ?? filterTenantId.value ?? tenantState.tenantId;
  modal.code = record?.code ?? '';
  modal.name = record?.name ?? '';
  modal.description = record?.description ?? '';
}

async function submit(): Promise<void> {
  if (!modal.tenantId) {
    message.error('请选择所属租户');
    return;
  }
  if (!modal.code.trim() || !modal.name.trim()) {
    message.error('编码与名称都不能为空');
    return;
  }
  saving.value = true;
  try {
    const payload = {
      code: modal.code.trim(),
      name: modal.name.trim(),
      description: modal.description?.trim() || undefined,
    };
    if (modal.id) {
      const existing = allRows.value.find((p) => p.id === modal.id);
      await updateProject(modal.tenantId, modal.id, { ...payload, status: existing?.status ?? 1 });
    } else {
      await createProject(modal.tenantId, payload);
    }
    modal.open = false;
    message.success('已保存');
    await load();
  } catch (e: any) {
    message.error(e?.message || String(e));
  } finally {
    saving.value = false;
  }
}

async function toggle(record: ProjectRow): Promise<void> {
  try {
    await updateProject(record.tenantId, record.id, {
      code: record.code,
      name: record.name,
      description: record.description,
      status: record.enabled ? 0 : 1,
    });
    await load();
  } catch (e: any) {
    message.error(e?.message || String(e));
  }
}

async function remove(record: ProjectRow): Promise<void> {
  try {
    await deleteProject(record.tenantId, record.id);
    message.success('已删除');
    await load();
  } catch (e: any) {
    // 409 的消息里带着剩余行数与替代方案，值得完整显示，别截断
    message.error({ content: e?.message || String(e), duration: 6 });
  }
}

onMounted(load);
</script>

<style scoped>
.w-240 { width: 240px; }

.count {
  margin-left: auto;
  font-size: 13px;
}

.ml-2 { margin-left: 8px; }
.mt-3 { margin-top: 12px; }

.danger { color: #cf1322; }

.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
  pointer-events: none;
}
</style>
