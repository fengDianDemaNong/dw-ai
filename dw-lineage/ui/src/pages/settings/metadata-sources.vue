<template>
  <ConfigProvider :wave="{ disabled: true }">
    <div class="metadata-page">
      <main class="page-body">
        <Alert
          v-if="capabilities && !capabilities.credentialEncryptionAvailable"
          type="warning"
          show-icon
          class="mb-4"
          message="未配置凭据加密密钥，dbx 类型无法保存"
        >
          <template #description>
            <p class="mb-2">
              服务端没有 <code>METADATA_SECRET_KEY</code>，凡是带凭据的服务（dbx 需要登录密码）都存不进去。
              这是刻意的：宁可保存失败，也不把凭据明文写进数据库。
            </p>
            <p>
              在<b>启动服务前</b>设置该环境变量，或写进安装目录的 <code>conf/env.sh</code>，然后重启：
            </p>
            <pre class="hint-code">export METADATA_SECRET_KEY='换成你自己的随机字符串'
bin/restart.sh</pre>
            <p class="hint-warn">注意：密钥更换后已存的凭据将无法解密，需要重新录入。</p>
          </template>
        </Alert>

        <!-- 上面那条 METADATA_SECRET_KEY 警告刻意不收进抽屉 ——
             它是当前就挡着你保存的问题，不是背景知识 -->
        <PageHeader title="元数据服务" subtitle="解析 SQL 时到哪里去查表结构">
          <template #actions>
            <Button @click="load" :loading="loading">刷新</Button>
            <Button type="primary" class="bg-[#1677ff]" @click="openCreate">新增服务</Button>
          </template>
          <template #help>
            <p>两种外部服务各有侧重：</p>
            <p>
              <b>Gravitino</b>：直连 HMS，能拿到<b>分区列</b>，
              Hive / Spark / Iceberg 等分区表场景的首选。
            </p>
            <p>
              <b>dbx</b>：通用数据库管理工具，<b>OLTP 与 OLAP 都覆盖</b>，
              原生 + Agent 驱动支持 80+ 种数据库
              （MySQL / PostgreSQL / ClickHouse / Doris / StarRocks / Oracle / TiDB …，
              Agent 方式还可扩展到 Hive / Trino / Databricks / Snowflake 等）。
              唯一的限制是实测其 schema 接口<b>不返回分区列</b>，
              解析分区表时分区字段可能缺失，建议用 Gravitino 交叉验证。
            </p>
            <p>
              还有一个内置的<b>本地元数据目录</b>，读的是「元数据」里维护的那份结构，
              不走网络。它不能删，不需要时停用即可。
            </p>
            <p>多个源同时启用时按<b>优先级</b>依次尝试。</p>
          </template>
        </PageHeader>

        <Table
          :columns="columns"
          :data-source="sources"
          :loading="loading"
          row-key="id"
          size="middle"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'type'">
              <Tag :color="typeColor(record.type)">{{ record.type }}</Tag>
              <Tag v-if="record.type === 'CATALOG'" color="default">内置</Tag>
            </template>
            <template v-else-if="column.key === 'credential'">
              <span :class="record.credentialConfigured ? 'text-green-600' : 'text-gray-400'">
                {{ record.credentialConfigured ? '已配置' : '未配置' }}
              </span>
            </template>
            <template v-else-if="column.key === 'enabled'">
              <Tag :color="record.enabled ? 'green' : 'default'">
                {{ record.enabled ? '启用' : '停用' }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'scope'">
              <span class="scope-text">{{ record.applicableScope }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="row-actions">
                <Button type="link" size="small" @click="testExisting(record as MetadataSource)"
                  :loading="testingId === record.id">测试连接</Button>
                <Button type="link" size="small" @click="openEdit(record as MetadataSource)">编辑</Button>
                <Popconfirm
                  v-if="record.type !== 'CATALOG'"
                  :title="`确定删除「${record.name}」吗？`"
                  ok-text="删除"
                  cancel-text="取消"
                  @confirm="remove(record as MetadataSource)"
                >
                  <Button type="link" size="small" danger>删除</Button>
                </Popconfirm>
                <Tooltip v-else title="内置来源不能删除。不需要的话把它停用即可">
                  <Button type="link" size="small" disabled>删除</Button>
                </Tooltip>
              </div>
            </template>
          </template>
        </Table>
      </main>

      <Modal
        v-model:open="modalOpen"
        :title="editingId ? '编辑元数据服务' : '新增元数据服务'"
        :confirm-loading="saving"
        ok-text="保存"
        cancel-text="取消"
        width="640px"
        @ok="save"
      >
        <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }" class="mt-4">
          <FormItem label="名称" required>
            <Input v-model:value="form.name" placeholder="便于识别的名称，如 gravitino-生产" />
          </FormItem>

          <FormItem label="类型" required>
            <Select
              v-model:value="form.type"
              :disabled="isLocalCatalog"
              :options="typeOptions"
              @change="onTypeChange"
            />
            <div v-if="isLocalCatalog" class="field-hint">
              内置的本地元数据目录：读的是「元数据」页面里维护的那份结构，
              不走网络，所以没有地址和凭据。这里只能调优先级和启停。
            </div>
          </FormItem>

          <FormItem v-if="!isLocalCatalog" label="服务地址" required>
            <Input v-model:value="form.baseUrl" :placeholder="baseUrlPlaceholder" />
          </FormItem>

          <FormItem v-if="!isLocalCatalog" label="凭据">
            <Input.Password
              v-model:value="form.credential"
              :placeholder="credentialPlaceholder"
            />
            <div class="field-hint">
              加密后存库，任何接口都不会回传明文。{{
                form.type === 'DBX' ? 'dbx 需要填登录密码。' : 'Gravitino 当前匿名访问，可留空。'
              }}
            </div>
            <div v-if="credentialBlocked" class="field-error">
              服务端未配置 METADATA_SECRET_KEY，填了凭据将无法保存（详见页面顶部提示）
            </div>
          </FormItem>

          <FormItem v-if="!isLocalCatalog" label="额外配置">
            <Textarea
              v-model:value="form.extraConfig"
              :rows="4"
              :placeholder="extraConfigPlaceholder"
            />
            <div class="field-hint">
              <template v-if="form.type === 'DBX'">
                JSON。<b>必须包含 connectionId</b>，即 dbx 中已保存的那个数据库连接的 id；
                database / schema 用于 SQL 里没写库名和 schema 时的默认值。
              </template>
              <template v-else>
                JSON，可选。填了 metalake / catalog 后，解析时可以不用每次再选一遍。
              </template>
            </div>
          </FormItem>

          <FormItem label="优先级">
            <InputNumber v-model:value="form.priority" :min="0" :max="9999" style="width: 120px" />
            <span class="field-hint ml-3">数字小的优先。多个服务时决定先问谁。</span>
          </FormItem>

          <FormItem label="启用">
            <Switch v-model:checked="form.enabled" />
          </FormItem>

          <FormItem :wrapper-col="{ offset: 5, span: 19 }">
            <Button @click="testForm" :loading="testingForm">测试连接</Button>
            <span
              v-if="testResult"
              class="ml-3"
              :class="testResult.success ? 'text-green-600' : 'text-red-500'"
            >{{ testResult.message }}</span>
          </FormItem>
        </Form>
      </Modal>
    </div>
  </ConfigProvider>
</template>

<script lang="ts" setup>
import PageHeader from '../../components/PageHeader/index.vue';
import { computed, onMounted, reactive, ref } from 'vue';
import {
  Alert,
  Button,
  ConfigProvider,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Switch,
  Table,
  Tag,
  Tooltip,
  message,
} from 'ant-design-vue';
import {
  createMetadataSource,
  deleteMetadataSource,
  getMetadataCapabilities,
  listMetadataSources,
  testMetadataSource,
  updateMetadataSource,
} from '../../services/api';
import type {
  MetadataCapabilities,
  MetadataSource,
  MetadataSourcePayload,
  MetadataSourceTestResult,
  MetadataSourceType,
} from '../../services/api';

const FormItem = Form.Item;
const Textarea = Input.TextArea;

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
  { title: '类型', dataIndex: 'type', key: 'type', width: 120 },
  { title: '服务地址', dataIndex: 'baseUrl', key: 'baseUrl', ellipsis: true },
  { title: '凭据', key: 'credential', width: 90 },
  { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
  { title: '状态', key: 'enabled', width: 80 },
  { title: '适用范围', key: 'scope', width: 260 },
  { title: '操作', key: 'action', width: 210 },
];

const sources = ref<MetadataSource[]>([]);
/** 「两种服务各有侧重」是否展开。默认收起。 */

const capabilities = ref<MetadataCapabilities | null>(null);
const loading = ref(false);
const saving = ref(false);
const testingForm = ref(false);
const testingId = ref<number | null>(null);
const modalOpen = ref(false);
const editingId = ref<number | null>(null);
const testResult = ref<MetadataSourceTestResult | null>(null);

const form = reactive<MetadataSourcePayload>({
  name: '',
  type: 'GRAVITINO',
  baseUrl: '',
  credential: '',
  extraConfig: '',
  priority: 100,
  enabled: true,
});

/** CATALOG 是内置的本地目录，只能在这里排优先级与启停，不能新建也不能删。 */
const typeOptions = [
  { value: 'GRAVITINO', label: 'Gravitino（直连 HMS，可取分区列）' },
  { value: 'DBX', label: 'dbx（80+ 种数据库，无分区列）' },
];

const TYPE_COLORS: Record<string, string> = {
  GRAVITINO: 'blue',
  DBX: 'orange',
  CATALOG: 'green',
};

const typeColor = (type: string) => TYPE_COLORS[type] ?? 'default';

/** 本地目录没有地址与凭据，相关表单项要藏起来，否则用户会以为漏填了。 */
const isLocalCatalog = computed(() => form.type === 'CATALOG');

const baseUrlPlaceholder = computed(() =>
  form.type === 'DBX' ? 'http://localhost:4224' : 'http://localhost:8090'
);

// 编辑已有配置时凭据是留空的，必须说清楚「留空 = 不改」而不是「留空 = 清空」
const credentialPlaceholder = computed(() =>
  editingId.value && sources.value.find((s) => s.id === editingId.value)?.credentialConfigured
    ? '已配置，留空表示不修改'
    : form.type === 'DBX'
      ? 'dbx 登录密码'
      : '可留空'
);

const extraConfigPlaceholder = computed(() =>
  form.type === 'DBX'
    ? '{"connectionId": "pgprobe", "database": "kohakuhub", "schema": "public"}'
    : '{"metalake": "demo", "catalog": "hive_catalog"}'
);

async function load() {
  loading.value = true;
  try {
    sources.value = await listMetadataSources();
  } catch (e: any) {
    message.error('加载元数据服务失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

async function loadCapabilities() {
  try {
    capabilities.value = await getMetadataCapabilities();
  } catch {
    // 拿不到能力信息不影响主流程，只是少一条提示
    capabilities.value = null;
  }
}

function resetForm(source?: MetadataSource) {
  form.name = source?.name ?? '';
  form.type = source?.type ?? 'GRAVITINO';
  form.baseUrl = source?.baseUrl ?? '';
  form.credential = '';
  form.extraConfig = source?.extraConfig ?? '';
  form.priority = source?.priority ?? 100;
  form.enabled = source?.enabled ?? true;
  testResult.value = null;
}

function openCreate() {
  editingId.value = null;
  resetForm();
  modalOpen.value = true;
}

function openEdit(source: MetadataSource) {
  editingId.value = source.id;
  resetForm(source);
  modalOpen.value = true;
}

// antd 的 Select @change 回调签名是 (value: SelectValue, option)，这里只关心 value
function onTypeChange(value: unknown) {
  const type = value as MetadataSourceType;
  // 换类型后原来的连通性结论已经无效，留着会误导
  testResult.value = null;
  if (!form.baseUrl) {
    form.baseUrl = type === 'DBX' ? 'http://localhost:4224' : 'http://localhost:8090';
  }
}

/** 服务端没配加密密钥时，凡是要存凭据的操作都会失败，在提交前就拦下来。 */
const credentialBlocked = computed(
  () =>
    capabilities.value?.credentialEncryptionAvailable === false &&
    !!form.credential
);

function validate(): string | null {
  if (!form.name?.trim()) return '请填写名称';
  // 本地目录不走网络，没有地址可填
  if (!isLocalCatalog.value && !form.baseUrl?.trim()) return '请填写服务地址';
  if (credentialBlocked.value) {
    // 与其让后端抛「未配置凭据加密密钥」再弹一次，不如在这里就说清楚
    return '服务端未配置 METADATA_SECRET_KEY，无法保存凭据。请先设置该环境变量并重启服务，或先清空凭据保存';
  }
  return null;
}

async function save() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }

  saving.value = true;
  try {
    const payload: MetadataSourcePayload = {
      ...form,
      // 留空表示不修改；新建时后端也把空串当作没有凭据
      credential: form.credential || undefined,
      extraConfig: form.extraConfig || undefined,
    };
    if (editingId.value) {
      await updateMetadataSource(editingId.value, payload);
      message.success('已保存');
    } else {
      await createMetadataSource(payload);
      message.success('已新增');
    }
    modalOpen.value = false;
    await load();
  } catch (e: any) {
    message.error('保存失败：' + (e?.message || e));
  } finally {
    saving.value = false;
  }
}

async function remove(source: MetadataSource) {
  try {
    await deleteMetadataSource(source.id);
    message.success(`已删除「${source.name}」`);
    await load();
  } catch (e: any) {
    message.error('删除失败：' + (e?.message || e));
  }
}

/** 测试表单里当前填的内容，可以在保存前先试。 */
async function testForm() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }
  testingForm.value = true;
  testResult.value = null;
  try {
    testResult.value = await testMetadataSource({
      // 带上 id：凭据留空时后端会用库里已保存的那份
      id: editingId.value ?? undefined,
      type: form.type,
      baseUrl: form.baseUrl,
      credential: form.credential || undefined,
      extraConfig: form.extraConfig || undefined,
    });
  } catch (e: any) {
    testResult.value = { success: false, message: e?.message || String(e) };
  } finally {
    testingForm.value = false;
  }
}

/** 直接测列表里某一条，不用先打开编辑框。 */
async function testExisting(source: MetadataSource) {
  testingId.value = source.id;
  try {
    const result = await testMetadataSource({ id: source.id });
    if (result.success) {
      message.success(`「${source.name}」${result.message}`);
    } else {
      message.error(`「${source.name}」连接失败：${result.message}`);
    }
  } catch (e: any) {
    message.error(`「${source.name}」测试失败：` + (e?.message || e));
  } finally {
    testingId.value = null;
  }
}

onMounted(() => {
  load();
  loadCapabilities();
});
</script>

<style scoped>
.metadata-page {
  height: 100%;
  overflow: auto;
}

.page-body {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
}

.scope-text {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}

.row-actions {
  display: flex;
  gap: 2px;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.6;
}

.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: #d4380d;
  line-height: 1.6;
}

.hint-code {
  margin: 6px 0;
  padding: 8px 10px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
}

.hint-warn {
  margin-top: 6px;
  color: #d4380d;
}

.mb-2 {
  margin-bottom: 8px;
}

.mb-4 {
  margin-bottom: 16px;
}

.mt-4 {
  margin-top: 16px;
}

.ml-3 {
  margin-left: 12px;
}

.tip-body {
  margin-top: 6px;
}
</style>
