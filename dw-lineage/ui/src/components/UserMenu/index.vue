<template>
  <div v-if="me" class="user-menu">
    <Popover trigger="click" placement="bottomRight" :overlay-style="{ width: '228px' }">
      <button type="button" class="trigger">
        <span class="avatar">{{ initial }}</span>
        <span class="name">{{ me.displayName || me.username }}</span>
        <DownOutlined class="caret" />
      </button>

      <template #content>
        <div class="panel">
          <div class="who">
            <div class="who-name">{{ me.displayName || me.username }}</div>
            <div class="who-sub">
              {{ me.username }}
              <Tag v-if="me.admin" color="blue">管理员</Tag>
            </div>
          </div>

          <button type="button" class="item" @click="openProfile">
            <IdcardOutlined class="item-icon" />
            修改资料
          </button>
          <router-link v-if="me.admin" class="item" :to="WORKBENCH_PAGES.users">
            <UserOutlined class="item-icon" />
            账号管理
          </router-link>
          <!-- 在项目里时才出现：工作台就是「上一层」，站在上面再给个回去的入口没有意义 -->
          <button v-if="!inWorkbench" type="button" class="item" @click="toWorkbench">
            <HomeOutlined class="item-icon" />
            返回工作台
          </button>
          <div class="sep" />
          <button type="button" class="item item-danger" :disabled="leaving" @click="doLogout">
            <LogoutOutlined class="item-icon" />
            退出登录
          </button>
        </div>
      </template>
    </Popover>

    <Modal
      v-model:open="profileOpen"
      title="修改资料"
      :confirm-loading="saving"
      ok-text="保存显示名"
      cancel-text="关闭"
      @ok="saveDisplayName"
    >
      <div class="section">
        <label class="label">显示名</label>
        <Input v-model:value="displayName" placeholder="展示给别人看的名字" />
      </div>

      <div class="sep-line" />

      <div class="section">
        <label class="label">修改密码</label>
        <p class="hint">改完会用新密码重新登录一次 —— 后端会同时作废所有刷新令牌。</p>
        <Input.Password v-model:value="currentPassword" class="field" placeholder="当前密码" />
        <Input.Password v-model:value="newPassword" class="field" placeholder="新密码（至少 4 位）" />
        <Input.Password v-model:value="confirmPassword" class="field" placeholder="再输一次新密码" />
        <Button :loading="changing" @click="savePassword">修改密码</Button>
      </div>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Button, Input, Modal, Popover, Tag, message } from 'ant-design-vue';
import {
  DownOutlined,
  HomeOutlined,
  IdcardOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons-vue';
import { WORKBENCH_HOME, WORKBENCH_PAGES, isWorkbenchPath } from '../../config/pages';
import { authState, changePassword, logout, updateProfile } from '../../stores/auth';

/**
 * 顶栏右上角的用户菜单。
 *
 * <p>只在 standard 模式、且已登录时出现 —— `me` 为空就整个不渲染。
 * 另外两种模式没有本地身份（standalone 放行、multi 走组织），顶栏不该多出一个人名。
 *
 * <p>「返回工作台」留在菜单里，是因为 standard 下它的出现条件与菜单完全一致（要有
 * 本地身份），分开摆只会多一处模式判断。**但 standalone 也有工作台、却没有本地身份**：
 * 那个模式的入口由 `AppTopbar` 单开一个按钮承担，两者的 `v-if` 正好互补，不会同时出现。
 * 这里早前写的是「工作台是 standard 专有的层级切换」，入口就跟着菜单一起收在了
 * `v-if="me"` 里，独立模式下进了项目再也回不去 —— 别再按那个说法收口。
 *
 * <h2>为什么「退出登录」必须在这里</h2>
 *
 * <p>无状态 JWT 没有服务端会话，但后端把 refresh 令牌落了库，登出是<b>真的</b>能撤销的
 * （删掉那行，最长 15 分钟后这个身份的 access 也自然过期）。没有这个入口，
 * 用户就只能靠关标签页 —— 那并不会撤销任何东西，共用电脑时是个真实的隐患。
 *
 * <h2>「改完密码要重新登录」不是刁难</h2>
 *
 * <p>后端在改密码成功后删掉该账号<b>全部</b>刷新令牌，包括本次会话正在用的那枚。
 * 不主动踢一次的话，用户会「改完还好好地用着」，直到 15 分钟后某个请求突然 401 ——
 * 那时他早就忘了自己改过密码，只会觉得系统坏了。所以这里立刻登出并说明原因。
 */
const me = computed(() => authState.me);

const initial = computed(() => {
  const source = me.value?.displayName || me.value?.username || '';
  return source.slice(0, 1).toUpperCase();
});

const route = useRoute();
const router = useRouter();

/** 已经在工作台里了就不再给「返回工作台」（见模板）。 */
const inWorkbench = computed(() => isWorkbenchPath(route.path));

/**
 * 回工作台。
 *
 * <p>只跳路径，不碰项目上下文 —— `tenantState.projectId` 是请求头概念、恒有值，
 * 两个层级的区别纯粹是地址位置：工作台的「概况」看全部项目口径，
 * 项目级的「概况」看当前项目口径。这一点与 dw-model 不同，那边有
 * `currentProjectId = null` 这个状态要清。
 *
 * <p>能点到这一项就说明 `me` 有值、即 standard，而 standard 必然有工作台 ——
 * 不必再判 `hasWorkbench()`。standalone 的入口不在这里（它没有 `me`），
 * 见组件说明以及 `AppTopbar` 里那个按钮。
 */
function toWorkbench(): void {
  router.push(WORKBENCH_HOME);
}

const leaving = ref(false);

async function doLogout(): Promise<void> {
  if (leaving.value) return;
  leaving.value = true;
  await logout();
  // 整页重载，让路由守卫去算登录页地址。重载也顺手清掉上一个身份留在页面上的数据 ——
  // 共用电脑时这一点比省一次加载重要。
  window.location.reload();
}

// ---------------------------------------------------------------------------
// 修改资料
// ---------------------------------------------------------------------------

const profileOpen = ref(false);
const displayName = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const saving = ref(false);
const changing = ref(false);

function openProfile(): void {
  displayName.value = me.value?.displayName ?? '';
  currentPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
  profileOpen.value = true;
}

async function saveDisplayName(): Promise<void> {
  const name = displayName.value.trim();
  if (!name) {
    message.warning('请填写显示名');
    return;
  }
  if (name === me.value?.displayName) {
    profileOpen.value = false;
    return;
  }
  saving.value = true;
  try {
    await updateProfile(name);
    message.success('显示名已更新');
    profileOpen.value = false;
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function savePassword(): Promise<void> {
  if (!currentPassword.value || !newPassword.value) {
    message.warning('请填写当前密码和新密码');
    return;
  }
  if (newPassword.value.length < 4) {
    message.warning('新密码至少 4 位');
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    message.warning('两次输入的新密码不一致');
    return;
  }
  changing.value = true;
  try {
    await changePassword(currentPassword.value, newPassword.value);
    message.success('密码已修改，请用新密码重新登录');
    // 见组件说明：后端已作废全部刷新令牌，这里主动退一次，别让人 15 分钟后突然被踢
    await logout();
    window.location.reload();
  } catch (e) {
    message.error(e instanceof Error ? e.message : '修改失败');
  } finally {
    changing.value = false;
  }
}
</script>

<style scoped>
.trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  color: #1f2937;
}

.trigger:hover {
  background: #f5f6f8;
  border-color: #f0f0f0;
}

.avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 12px;
  line-height: 24px;
  text-align: center;
  flex-shrink: 0;
}

.name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.caret {
  font-size: 10px;
  color: #9ca3af;
}

.panel {
  margin: -12px;
}

.who {
  padding: 12px 14px;
  border-bottom: 1px solid #f0f0f0;
}

.who-name {
  font-size: 14px;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.who-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #9ca3af;
  display: flex;
  align-items: center;
  gap: 6px;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 14px;
  border: none;
  background: transparent;
  color: #1f2937;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.item:hover {
  background: #f5f6f8;
}

.item:disabled {
  color: #9ca3af;
  cursor: default;
}

.item-danger {
  color: #cf1322;
}

.item-icon {
  font-size: 14px;
}

.sep {
  height: 1px;
  background: #f0f0f0;
}

.section {
  margin-bottom: 8px;
}

.label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: #4b5563;
}

.hint {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #9ca3af;
}

.field {
  margin-bottom: 8px;
}

.sep-line {
  height: 1px;
  margin: 16px 0;
  background: #f0f0f0;
}
</style>
