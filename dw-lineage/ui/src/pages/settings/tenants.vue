<template>
  <div class="page">
    <PageHeader title="租户" subtitle="数据隔离的维度，不是权限边界">
      <template #actions>
        <Button :loading="loading" @click="load">刷新</Button>
        <Button type="primary" class="bg-[#1677ff]" @click="openModal()">新增租户</Button>
      </template>
      <template #help>
        <p>
          <b>谁能改</b>：普通模式（standard）下只有<b>管理员</b>能建 / 改 / 删租户与项目；
          独立模式没有账号体系，能访问本服务的人都可以；多租户模式下租户归组织平台管，
          这一页的写操作会被拒绝。
        </p>
        <p>
          但请注意：这里管的是「谁能改租户」，不是「谁能读某个租户的数据」。
          登录只回答「你是谁」，不限制「你能碰哪个租户」—— 这是隔离，还不是权限边界。
        </p>
        <p>
          每个租户 / 项目的血缘、元数据、元数据服务配置彼此<b>完全隔离</b>。
        </p>
        <p>
          新建租户时会自动生成一个默认项目，并为它配好内置的「本地元数据目录」。
        </p>
        <p>
          删除只在其下没有任何业务数据时才允许，默认租户删不掉。后端拒绝时会说明
          还剩多少行数据。
        </p>
        <p>
          点任意一行进「设置 › 项目」，看这个租户下的项目。
        </p>
      </template>
    </PageHeader>

    <div class="card card-flush">
      <Table
        :columns="columns"
        :data-source="tenants"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="small"
        :custom-row="rowEvents"
        class="clickable-rows"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span>{{ record.name }}</span>
            <Tag v-if="record.id === currentTenantId" color="blue" class="ml-2">当前</Tag>
            <Tag v-if="record.id === DEFAULT_TENANT_ID" class="ml-2">默认</Tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="record.enabled ? 'green' : 'default'">
              {{ record.enabled ? '启用' : '停用' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'projects'">
            <!-- 数字本身就是入口，比再加一个「查看」按钮省一列 -->
            <a @click.stop="openProjects(record.id)">{{ record.projects.length }}</a>
          </template>
          <template v-else-if="column.key === 'action'">
            <!-- antd 把 bodyCell 的 record 定为 Record<string, any>，这里断言回具体类型 -->
            <a @click.stop="openModal(record as Tenant)">编辑</a>
            <a class="ml-2" @click.stop="toggle(record as Tenant)">
              {{ record.enabled ? '停用' : '启用' }}
            </a>
            <Popconfirm
              title="确定删除该租户？仅在其下没有任何业务数据时可删。"
              @confirm="remove(record as Tenant)"
            >
              <a class="ml-2 danger" :class="{ disabled: record.id === DEFAULT_TENANT_ID }"
                 @click.stop>删除</a>
            </Popconfirm>
          </template>
        </template>
      </Table>
    </div>

    <Modal v-model:open="modal.open" :title="modal.id ? '编辑租户' : '新增租户'"
           :confirm-loading="saving" @ok="submit">
      <Form layout="vertical" class="mt-3">
        <FormItem label="租户编码" :help="modal.id ? '编码创建后不可修改' : CODE_HELP">
          <Input v-model:value="modal.code" :disabled="!!modal.id" placeholder="如 acme" />
        </FormItem>
        <FormItem label="租户名称">
          <Input v-model:value="modal.name" placeholder="如 ACME 数据平台" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  Button, Form, FormItem, Input, Modal, Popconfirm, Table, Tag, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import { createTenant, deleteTenant, listTenants, updateTenant, type Tenant } from '../../services/api';
import { DEFAULT_TENANT_ID, tenantState } from '../../stores/tenant';

/**
 * 租户管理。
 *
 * 项目拆到了「设置 › 项目」单独一页：原先左右分栏、右边跟着左边选中的租户变，
 * 想看某个租户的项目必须先在左表里点中它，也没法一眼看到全部项目。
 * 点这里的任意一行就带着 tenantId 跳过去。
 */
const CODE_HELP = '只能用小写字母、数字、下划线和短横线，创建后不可修改';

const router = useRouter();

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '编码', dataIndex: 'code', key: 'code', width: 140 },
  { title: '状态', key: 'status', width: 90 },
  { title: '项目数', key: 'projects', width: 80 },
  { title: '操作', key: 'action', width: 170 },
];

const tenants = ref<Tenant[]>([]);
const loading = ref(false);
const saving = ref(false);

const currentTenantId = computed(() => tenantState.tenantId);

const modal = reactive({ open: false, id: 0, code: '', name: '' });

const rowEvents = (record: Tenant) => ({ onClick: () => openProjects(record.id) });

function openProjects(tenantId: number) {
  router.push({ path: '/lineage/settings/projects', query: { tenantId: String(tenantId) } });
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    tenants.value = await listTenants();
  } catch (e: any) {
    message.error('加载租户失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

function openModal(record?: Tenant): void {
  modal.open = true;
  modal.id = record?.id ?? 0;
  modal.code = record?.code ?? '';
  modal.name = record?.name ?? '';
}

async function submit(): Promise<void> {
  if (!modal.code.trim() || !modal.name.trim()) {
    message.error('编码与名称都不能为空');
    return;
  }
  saving.value = true;
  try {
    if (modal.id) {
      const existing = tenants.value.find((t) => t.id === modal.id);
      await updateTenant(modal.id, {
        code: modal.code,
        name: modal.name.trim(),
        status: existing?.status ?? 1,
      });
    } else {
      await createTenant({ code: modal.code.trim(), name: modal.name.trim() });
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

async function toggle(record: Tenant): Promise<void> {
  try {
    await updateTenant(record.id, {
      code: record.code,
      name: record.name,
      status: record.enabled ? 0 : 1,
    });
    await load();
  } catch (e: any) {
    message.error(e?.message || String(e));
  }
}

async function remove(record: Tenant): Promise<void> {
  try {
    await deleteTenant(record.id);
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
.ml-2 { margin-left: 8px; }
.mt-3 { margin-top: 12px; }

.danger { color: #cf1322; }

.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
  pointer-events: none;
}

.clickable-rows :deep(.ant-table-row) {
  cursor: pointer;
}
</style>
