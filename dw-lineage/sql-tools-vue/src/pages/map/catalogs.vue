<template>
  <div class="page">
    <PageHeader title="数据目录" subtitle="表全名的第一段，参与表的唯一性">
      <template #actions>
        <Button @click="loadCatalogs" :loading="catalogLoading">刷新</Button>
        <Button type="primary" class="bg-[#1677ff]" @click="openCatalogCreate">新增目录</Button>
      </template>
      <template #help>
        <p>每个项目必须有一个<b>默认数据目录</b>。</p>
        <p>
          贴建表语句导入元数据时若没指定目录，就落到默认目录，因此本地元数据里不存在
          「没有数据目录」的表，表全名恒为三段 <code>数据目录.库.表</code>。
        </p>
        <p>
          数据目录参与表的唯一性 —— <code>hive_prod.ods.orders</code> 与
          <code>hive_test.ods.orders</code> 是两张不同的表。
        </p>
        <p>
          命中<b>临时表规则</b>的库表不进血缘图也不入库，规则在「数据地图 › 临时表规则」里维护。
        </p>
      </template>
    </PageHeader>

    <div class="card card-flush">
      <Table
        :columns="catalogColumns"
        :data-source="catalogs"
        :loading="catalogLoading"
        row-key="id"
        size="small"
        :pagination="false"
      >
        <!-- antd 把 bodyCell 的 record 定为 Record<string, any>，下面断言回具体类型 -->
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span class="mono">{{ record.name }}</span>
            <Tag v-if="record.isDefault" color="blue" class="ml-2">默认</Tag>
          </template>

          <template v-else-if="column.key === 'action'">
            <Button type="link" size="small" @click="openCatalogEdit(record as DataCatalog)">编辑</Button>
            <Button
              v-if="!record.isDefault"
              type="link"
              size="small"
              @click="markDefault(record as DataCatalog)"
            >设为默认</Button>
            <!-- 默认目录、以及下面还有表的目录都删不掉。这里直接禁用并说明原因，
                 而不是让用户点下去被 409 挡回来 -->
            <Tooltip v-if="deleteBlockedReason(record as DataCatalog)" :title="deleteBlockedReason(record as DataCatalog)">
              <Button type="link" size="small" disabled>删除</Button>
            </Tooltip>
            <Popconfirm
              v-else
              :title="`确定删除数据目录「${record.name}」？`"
              @confirm="removeCatalog(record as DataCatalog)"
            >
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </template>
        </template>
      </Table>
    </div>

    <Modal
      v-model:open="catalogModalOpen"
      :title="editingCatalogId ? '编辑数据目录' : '新增数据目录'"
      :confirm-loading="catalogSaving"
      @ok="submitCatalog"
    >
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }">
        <FormItem label="名称" required>
          <Input v-model:value="catalogForm.name" placeholder="例如 hive_prod" />
          <div class="hint">
            只能用字母、数字、下划线和短横线。<b>不能含点号</b> ——
            它是表全名的第一段，带点会把全名的切分切错位。
          </div>
        </FormItem>
        <FormItem label="说明">
          <Textarea v-model:value="catalogForm.description" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue';
import {
  Button, Form, Input, Modal, Popconfirm, Table, Tag, Tooltip, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import {
  createDataCatalog, deleteDataCatalog, listDataCatalogs,
  setDefaultDataCatalog, updateDataCatalog,
} from '../../services/api';
import type { DataCatalog } from '../../services/api';

/**
 * 数据目录配置。
 *
 * 原先和「临时库表规则」挤在同一个页面里，两张不相干的表加两段长说明，
 * 打开先看半屏文字。现在各占一页，说明进 ? 抽屉。
 */
const FormItem = Form.Item;
const Textarea = Input.TextArea;

const catalogColumns = [
  { title: '名称', key: 'name', width: 220 },
  { title: '表数量', dataIndex: 'tableCount', key: 'tableCount', width: 100 },
  { title: '说明', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'action', width: 230 },
];

const catalogs = ref<DataCatalog[]>([]);
const catalogLoading = ref(false);
const catalogSaving = ref(false);
const catalogModalOpen = ref(false);
const editingCatalogId = ref<number | null>(null);

const catalogForm = reactive<{ name: string; description: string }>({
  name: '',
  description: '',
});

/** 不能删的原因；能删则返回空串。 */
function deleteBlockedReason(record: DataCatalog): string {
  if (record.isDefault) {
    return '默认数据目录不能删除。请先把另一个目录设为默认。';
  }
  if (record.tableCount > 0) {
    return `该目录下还有 ${record.tableCount} 张表，请先在「元数据」里删除它们。`;
  }
  return '';
}

async function loadCatalogs() {
  catalogLoading.value = true;
  try {
    catalogs.value = await listDataCatalogs();
  } catch (e) {
    message.error((e as Error).message || '加载数据目录失败');
  } finally {
    catalogLoading.value = false;
  }
}

function openCatalogCreate() {
  editingCatalogId.value = null;
  catalogForm.name = '';
  catalogForm.description = '';
  catalogModalOpen.value = true;
}

function openCatalogEdit(record: DataCatalog) {
  editingCatalogId.value = record.id;
  catalogForm.name = record.name;
  catalogForm.description = record.description ?? '';
  catalogModalOpen.value = true;
}

async function submitCatalog() {
  if (!catalogForm.name.trim()) {
    message.warning('请填写数据目录名称');
    return;
  }
  catalogSaving.value = true;
  try {
    const payload = {
      name: catalogForm.name.trim(),
      description: catalogForm.description || undefined,
    };
    if (editingCatalogId.value) {
      await updateDataCatalog(editingCatalogId.value, payload);
    } else {
      await createDataCatalog(payload);
    }
    catalogModalOpen.value = false;
    await loadCatalogs();
    message.success('已保存');
  } catch (e) {
    message.error((e as Error).message || '保存失败');
  } finally {
    catalogSaving.value = false;
  }
}

async function markDefault(record: DataCatalog) {
  try {
    await setDefaultDataCatalog(record.id);
    await loadCatalogs();
    message.success(`「${record.name}」已设为默认数据目录`);
  } catch (e) {
    message.error((e as Error).message || '设置失败');
  }
}

async function removeCatalog(record: DataCatalog) {
  try {
    await deleteDataCatalog(record.id);
    await loadCatalogs();
    message.success('已删除');
  } catch (e) {
    message.error((e as Error).message || '删除失败');
  }
}

onMounted(loadCatalogs);
</script>

<style scoped>
.ml-2 { margin-left: 8px; }
</style>
