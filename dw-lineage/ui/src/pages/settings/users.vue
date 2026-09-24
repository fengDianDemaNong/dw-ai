<template>
  <div class="page">
    <PageHeader title="账号管理" subtitle="本部署的本地账号">
      <template #actions>
        <Button :loading="loading" @click="load">刷新</Button>
        <Button type="primary" class="bg-[#1677ff]" @click="openCreate">新增账号</Button>
      </template>
      <template #help>
        <p>
          这一页只在<b>普通模式（standard）</b>下出现，管的是本进程自己的账号 ——
          独立模式没有账号体系，多租户模式的账号由组织平台统一管理。
        </p>
        <p>
          只有<b>管理员</b>能进来。前端会把非管理员挡在门外，但这只是省得点一个必然
          被拒的页面；真正的判权在服务端，普通用户即使直接调接口也会被拒。
        </p>
        <p>
          几条规则是服务端强制的，违反时会给出说明：不能停用或删除自己（会让当前会话
          立即失效）；不能让「可用的管理员」归零（那会让本部署再也无法登录，
          只能进库改）。
        </p>
        <p>
          <b>停用</b>会立刻撤销该账号的全部刷新令牌，他当前的登录最长再过 15 分钟失效；
          账号本身保留，可以随时再启用。<b>删除</b>是物理删除、不可恢复 ——
          想留追溯线索就用停用。
        </p>
        <p>
          这里看不到也取不回密码：库里存的是 BCrypt 哈希。忘了密码只能由管理员在这里
          重置，重置后该账号需要重新登录。
        </p>
      </template>
    </PageHeader>

    <div class="card card-flush">
      <Table
        :columns="columns"
        :data-source="users"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'username'">
            <span class="mono">{{ record.username }}</span>
            <Tag v-if="record.id === currentUserId" color="blue" class="ml-2">当前登录</Tag>
          </template>
          <template v-else-if="column.key === 'admin'">
            <Tag :color="record.admin ? 'gold' : 'default'">{{ record.admin ? '管理员' : '普通用户' }}</Tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="record.enabled ? 'green' : 'default'">
              {{ record.enabled ? '启用' : '停用' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <!-- antd 把 bodyCell 的 record 定为 Record<string, any>，断言回具体类型 -->
            <a @click="openEdit(record as LocalUser)">编辑</a>
            <a class="ml-2" @click="openReset(record as LocalUser)">重置密码</a>
            <!-- 对自己停用会立刻踢掉自己的会话，服务端也会拒；这里直接不给点 -->
            <Popconfirm
              v-if="record.id !== currentUserId"
              :title="record.enabled
                ? '停用后该账号会立刻被踢下线，且无法再登录。确定停用？'
                : '重新启用该账号？'"
              @confirm="toggle(record as LocalUser)"
            >
              <a class="ml-2">{{ record.enabled ? '停用' : '启用' }}</a>
            </Popconfirm>
            <span v-else class="ml-2 muted" title="不能停用自己的账号">停用</span>

            <Popconfirm
              v-if="record.id !== currentUserId"
              title="物理删除且不可恢复。想保留追溯线索请改用「停用」。确定删除？"
              ok-text="删除"
              @confirm="remove(record as LocalUser)"
            >
              <a class="ml-2 danger">删除</a>
            </Popconfirm>
            <span v-else class="ml-2 muted" title="不能删除自己的账号">删除</span>
          </template>
        </template>
      </Table>
    </div>

    <!-- 新建 / 编辑 -->
    <Modal
      v-model:open="modal.open"
      :title="modal.id ? '编辑账号' : '新增账号'"
      :confirm-loading="saving"
      @ok="submit"
    >
      <Form layout="vertical" class="mt-3">
        <FormItem label="用户名" :help="modal.id ? '用户名是稳定标识，创建后不可修改' : USERNAME_HELP">
          <Input v-model:value="modal.username" :disabled="!!modal.id" placeholder="如 zhangsan" />
        </FormItem>
        <FormItem label="显示名">
          <Input v-model:value="modal.displayName" placeholder="如 张三" />
        </FormItem>
        <FormItem v-if="!modal.id" label="初始密码" :help="PASSWORD_HELP">
          <Input.Password v-model:value="modal.password" placeholder="至少 4 位" />
        </FormItem>
        <FormItem label="管理员">
          <Switch v-model:checked="modal.admin" />
          <span class="muted ml-2">
            管理员可以管理本页所有账号；关掉后只能改自己的显示名与密码。
          </span>
        </FormItem>
      </Form>
    </Modal>

    <!-- 重置密码 -->
    <Modal
      v-model:open="reset.open"
      title="重置密码"
      :confirm-loading="saving"
      ok-text="重置"
      @ok="submitReset"
    >
      <p class="mt-3">
        为 <b>{{ reset.username }}</b> 设置一个新密码。
      </p>
      <p class="muted">
        不需要知道他的旧密码。提交后他的全部刷新令牌会被立刻撤销 ——
        这正是「怀疑密码泄露」时的止血动作，之后他要用新密码重新登录。
      </p>
      <Form layout="vertical" class="mt-3">
        <FormItem label="新密码" :help="PASSWORD_HELP">
          <Input.Password v-model:value="reset.password" placeholder="至少 4 位" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue';
import {
  Button, Form, FormItem, Input, Modal, Popconfirm, Switch, Table, Tag, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import {
  createLocalUser,
  deleteLocalUser,
  listLocalUsers,
  resetLocalUserPassword,
  setLocalUserStatus,
  updateLocalUser,
  type LocalUser,
} from '../../services/api';
import { authState } from '../../stores/auth';

/**
 * 本地账号管理（standard 模式，仅管理员）。
 *
 * <p>对应后端 `LocalUserController`（`/api/users`）。这一页只做「好看的表格 + 表单」，
 * 所有业务规则都在服务端：不能停用/删除自己、不能让可用管理员归零、
 * 停用与重置密码会撤销刷新令牌。前端的重复判断只有一处 ——
 * 把「对自己停用/删除」渲染成不可点，因为那是纯粹的误操作，没必要让他点一次看到报错。
 *
 * <p>错误消息一律原样展示：后端在 409 里写的说明（「这会让本部署再也无法登录，
 * 只能直接改数据库」）比任何前端改写都准确，截断或替换反而丢掉信息。
 */
const USERNAME_HELP = '只能用字母、数字、点、下划线和短横线，且以字母或数字开头';
const PASSWORD_HELP = '至少 4 位。不强制复杂度是有意的：本地部署的账号由管理员手工发放，规则太严只会催生写在便签上的密码。';

const columns = [
  { title: '用户名', dataIndex: 'username', key: 'username' },
  { title: '显示名', dataIndex: 'displayName', key: 'displayName' },
  { title: '身份', key: 'admin', width: 110 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  { title: '操作', key: 'action', width: 250 },
];

const users = ref<LocalUser[]>([]);
const loading = ref(false);
const saving = ref(false);

const currentUserId = computed(() => authState.me?.userId ?? 0);

const modal = reactive({
  open: false,
  id: 0,
  username: '',
  displayName: '',
  password: '',
  admin: false,
});

const reset = reactive({ open: false, id: 0, username: '', password: '' });

async function load(): Promise<void> {
  loading.value = true;
  try {
    users.value = await listLocalUsers();
  } catch (e: any) {
    message.error({ content: '加载账号失败：' + (e?.message || e), duration: 6 });
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  modal.open = true;
  modal.id = 0;
  modal.username = '';
  modal.displayName = '';
  modal.password = '';
  modal.admin = false;
}

function openEdit(record: LocalUser): void {
  modal.open = true;
  modal.id = record.id;
  modal.username = record.username;
  modal.displayName = record.displayName;
  modal.password = '';
  modal.admin = record.admin;
}

async function submit(): Promise<void> {
  if (!modal.id && !modal.username.trim()) {
    message.error('用户名不能为空');
    return;
  }
  if (!modal.displayName.trim()) {
    message.error('显示名不能为空');
    return;
  }
  if (!modal.id && modal.password.length < 4) {
    message.error('初始密码至少 4 位');
    return;
  }
  saving.value = true;
  try {
    if (modal.id) {
      await updateLocalUser(modal.id, {
        displayName: modal.displayName.trim(),
        admin: modal.admin,
      });
    } else {
      await createLocalUser({
        username: modal.username.trim(),
        displayName: modal.displayName.trim(),
        password: modal.password,
        admin: modal.admin,
      });
    }
    modal.open = false;
    message.success('已保存');
    await load();
  } catch (e: any) {
    message.error({ content: e?.message || String(e), duration: 6 });
  } finally {
    saving.value = false;
  }
}

function openReset(record: LocalUser): void {
  reset.open = true;
  reset.id = record.id;
  reset.username = record.username;
  reset.password = '';
}

async function submitReset(): Promise<void> {
  if (reset.password.length < 4) {
    message.error('新密码至少 4 位');
    return;
  }
  saving.value = true;
  try {
    await resetLocalUserPassword(reset.id, reset.password);
    reset.open = false;
    message.success('密码已重置，该账号需要重新登录');
  } catch (e: any) {
    message.error({ content: e?.message || String(e), duration: 6 });
  } finally {
    saving.value = false;
  }
}

async function toggle(record: LocalUser): Promise<void> {
  try {
    await setLocalUserStatus(record.id, record.enabled ? 0 : 1);
    await load();
  } catch (e: any) {
    message.error({ content: e?.message || String(e), duration: 6 });
  }
}

async function remove(record: LocalUser): Promise<void> {
  try {
    await deleteLocalUser(record.id);
    message.success('已删除');
    await load();
  } catch (e: any) {
    message.error({ content: e?.message || String(e), duration: 6 });
  }
}

onMounted(load);
</script>

<style scoped>
.ml-2 { margin-left: 8px; }
.mt-3 { margin-top: 12px; }

.danger { color: #cf1322; }

.muted {
  color: #9ca3af;
  cursor: not-allowed;
}
</style>
