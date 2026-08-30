<template>
  <a-dropdown :trigger="['click']" :placement="placement">
    <button type="button" class="me" :class="menuPos">
      <span class="av">{{ initial }}</span>
      <span class="who">
        <b>{{ sessionAccount?.displayName }}</b>
        <small>{{ contextLine }}</small>
      </span>
    </button>
    <template #overlay>
      <div class="sheet">
        <div class="bio">
          <div class="av lg">{{ initial }}</div>
          <div>
            <b>{{ sessionAccount?.displayName }}</b>
            <p>账号 {{ sessionAccount?.username }}</p>
            <p>{{ roleLine }}</p>
            <p v-if="project">项目 {{ project.name }}</p>
            <p>系统 产品原型 0.1.1</p>
          </div>
        </div>

        <template v-if="multi && tenants.length">
          <div class="lab">切换租户</div>
          <button
            v-for="t in tenants"
            :key="t.id"
            type="button"
            class="act"
            :class="{ on: t.id === tenant?.id }"
            @click="pick(t.id)"
          >
            {{ t.name }}
            <span v-if="t.id === tenant?.id" class="now">当前</span>
          </button>
        </template>

        <button type="button" class="act" @click="openProfile">个人信息</button>
        <button type="button" class="act" @click="openPwd">修改密码</button>
        <button v-if="isPlatformAdmin && !onAdmin" type="button" class="act" @click="toAdmin">进入平台后台</button>
        <button v-if="isRealTenantAdmin && project" type="button" class="act" @click="toWorkbench">返回工作台</button>
        <button type="button" class="act" @click="resetAndLeave">重置演示</button>
        <button type="button" class="act danger" @click="out">退出</button>
      </div>
    </template>
  </a-dropdown>

  <a-modal v-model:open="profileOpen" title="个人信息" ok-text="保存" @ok="saveProfile">
    <a-form layout="vertical">
      <a-form-item label="显示名" required>
        <a-input v-model:value="profileName" />
      </a-form-item>
      <a-form-item label="账号">
        <a-input :value="sessionAccount?.username" disabled />
      </a-form-item>
      <a-form-item label="身份">
        <a-input :value="roleLine.replace(/^身份 /, '')" disabled />
      </a-form-item>
      <a-form-item v-if="tenant" label="当前租户">
        <a-input :value="tenant.name" disabled />
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal v-model:open="pwdOpen" title="修改密码" ok-text="保存" @ok="savePwd">
    <a-form layout="vertical">
      <a-form-item label="当前密码" required>
        <a-input-password v-model:value="pwdCur" />
      </a-form-item>
      <a-form-item label="新密码" required>
        <a-input-password v-model:value="pwdNext" />
      </a-form-item>
      <a-form-item label="确认新密码" required>
        <a-input-password v-model:value="pwdAgain" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { PROJECT_ROLE_LABEL, TENANT_ROLE_LABEL } from '../config/iam';
import { ADMIN_HOME, SYS_HOME } from '../config/paths';
import { isMultiTenant } from '../config/runtime';
import type { MenuPos } from '../stores/prefs';
import {
  currentProject,
  currentProjectRole,
  currentTenant,
  leaveProject,
  isPlatformAdmin,
  isRealTenantAdmin,
  logout,
  resetDemo,
  selectableTenants,
  sessionAccount,
  changeOwnPassword,
  resolveTenantHome,
  switchTenant,
  tenantRoleOf,
  updateOwnProfile,
} from '../stores/app';

const props = defineProps<{
  menuPos: MenuPos;
}>();

const router = useRouter();
const route = useRoute();
const tenant = currentTenant;
const project = currentProject;
const multi = isMultiTenant();
const tenants = computed(() => selectableTenants());
const onAdmin = computed(() => route.path.startsWith('/admin'));
const placement = computed(() => (props.menuPos === 'left' ? 'topLeft' : 'bottomRight'));
const profileOpen = ref(false);
const pwdOpen = ref(false);
const profileName = ref('');
const pwdCur = ref('');
const pwdNext = ref('');
const pwdAgain = ref('');
const initial = computed(() => (sessionAccount.value?.displayName ?? '?').slice(0, 1));

const contextLine = computed(() => {
  if (onAdmin.value) return '平台后台';
  if (tenant.value) return tenant.value.name;
  return multi ? '未选租户' : '工作台';
});

const roleLine = computed(() => {
  if (isPlatformAdmin.value && onAdmin.value) return '身份 平台用户';
  const parts: string[] = [];
  if (isPlatformAdmin.value) parts.push('平台用户');
  const tr = sessionAccount.value ? tenantRoleOf(sessionAccount.value.id) : null;
  if (tr) parts.push(TENANT_ROLE_LABEL[tr]);
  const pr = currentProjectRole();
  if (pr) parts.push(PROJECT_ROLE_LABEL[pr]);
  return parts.length ? `身份 ${parts.join(' · ')}` : '身份 —';
});

function openProfile() {
  profileName.value = sessionAccount.value?.displayName ?? '';
  profileOpen.value = true;
}

function saveProfile() {
  if (!updateOwnProfile({ displayName: profileName.value })) return;
  profileOpen.value = false;
}

function openPwd() {
  pwdCur.value = '';
  pwdNext.value = '';
  pwdAgain.value = '';
  pwdOpen.value = true;
}

function savePwd() {
  if (pwdNext.value !== pwdAgain.value) {
    message.warning('两次新密码不一致');
    return;
  }
  if (!changeOwnPassword(pwdCur.value, pwdNext.value)) return;
  pwdOpen.value = false;
}

function pick(id: string) {
  if (!switchTenant(id)) return;
  router.push(resolveTenantHome());
}

function toAdmin() {
  router.push(ADMIN_HOME);
}

function toWorkbench() {
  leaveProject();
  router.push(SYS_HOME);
}

function resetAndLeave() {
  resetDemo();
  window.location.assign('/login');
}

function out() {
  logout();
  router.push('/login');
}
</script>

<style scoped>
.me {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: transparent;
  color: #e2e8f0;
  text-align: left;
  cursor: pointer;
}

.me.top {
  width: auto;
  max-width: 220px;
  padding: 4px 8px;
  border: 0;
  border-radius: 8px;
}

.me:hover {
  background: rgba(255, 255, 255, 0.04);
}

.av {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #0e7490;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 650;
  flex-shrink: 0;
}

.av.lg {
  width: 36px;
  height: 36px;
  font-size: 15px;
}

.who {
  min-width: 0;
}

.who b,
.bio b {
  display: block;
  font-size: 13px;
  line-height: 1.2;
}

.who small,
.bio p {
  display: block;
  margin: 2px 0 0;
  font-size: 11px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sheet {
  width: 260px;
  padding: 10px;
  background: #0b1220;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  color: #e2e8f0;
}

.bio {
  display: flex;
  gap: 10px;
  padding: 4px 4px 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  margin-bottom: 8px;
}

.lab {
  margin: 6px 4px 4px;
  font-size: 11px;
  color: #64748b;
  letter-spacing: 0.06em;
}

.act {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #cbd5e1;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
}

.act:hover,
.act.on {
  background: rgba(34, 211, 238, 0.1);
  color: #67e8f9;
}

.act.danger {
  margin-top: 4px;
  color: #fca5a5;
}

.act.danger:hover {
  background: rgba(220, 38, 38, 0.12);
  color: #fecaca;
}

.now {
  font-size: 11px;
  color: #67e8f9;
}
</style>
