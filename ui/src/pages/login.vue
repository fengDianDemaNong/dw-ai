<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>登录 · 智仓 DW-AI</h1>
          <p>当前启动：{{ modeLabel }}</p>
        </div>
      </div>

      <p class="hint">{{ hint }}</p>

      <a-form layout="vertical" @submit.prevent="submit">
        <a-form-item label="用户名">
          <a-input v-model:value="username" placeholder="请输入用户名" @pressEnter="submit" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="password" placeholder="密码" @pressEnter="submit" />
        </a-form-item>
        <a-button type="primary" size="large" block :loading="busy" @click="submit">登录</a-button>
      </a-form>
      <p v-if="err" class="err">{{ err }}</p>

      <div v-if="!remote" class="mode">
        <span>启动模式（改完刷新）</span>
        <a-radio-group :value="mode" size="small" @change="onMode">
          <a-radio-button value="multi">多租户</a-radio-button>
          <a-radio-button value="standard">普通</a-radio-button>
        </a-radio-group>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api, useRemoteApi } from '../api/client';
import { ADMIN_HOME } from '../config/paths';
import { getDeployMode, isMultiTenant, setDeployMode, setDeployModeHint, type DeployMode } from '../config/runtime';
import { ensureProjectSnapshot, loginDev, loginLocal, resolveTenantHome } from '../stores/app';

const router = useRouter();
const remote = useRemoteApi();
const username = ref('');
const password = ref('');
const busy = ref(false);
const err = ref('');
const mode = getDeployMode();
const modeLabel = computed(() => (isMultiTenant() ? '多租户' : '普通模式'));
const hint = computed(() =>
  isMultiTenant()
    ? '先认人，再选租户。租户管理员进工作台；其他用户直接进已加入的项目。'
    : '普通模式：没有平台后台。管理员进工作台，其他人进项目。'
);
function onMode(e: { target: { value: string | number | boolean } }) {
  setDeployMode(e.target.value as DeployMode);
}

async function submit() {
  err.value = '';
  busy.value = true;
  try {
    const user = username.value.trim();
    const out = remote ? await loginDev(user, password.value) : loginLocal(user, password.value);
    if (out.platformAdmin) {
      router.push(ADMIN_HOME);
      return;
    }
    if (!isMultiTenant()) {
      const next = resolveTenantHome();
      if (next === '/w') await ensureProjectSnapshot();
      router.push(next);
      return;
    }
    if (out.needSelectTenant || (out.tenants && out.tenants.length > 1)) {
      router.push('/select-tenant');
      return;
    }
    const next = resolveTenantHome();
    if (next === '/w') await ensureProjectSnapshot();
    router.push(next);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '用户名或密码错误';
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  if (!remote) return;
  try {
    const cfg = await api.authConfig();
    if (cfg.deployMode && cfg.deployMode !== getDeployMode()) {
      setDeployModeHint(cfg.deployMode);
      window.location.reload();
    }
  } catch {
    /* 登录页仍可用 */
  }
});
</script>

<style scoped>
.login {
  min-height: 100%;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 500px at 10% -10%, rgba(34, 211, 238, 0.18), transparent),
    #070b14;
}

.panel {
  width: 520px;
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.35);
}

.logo-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.logo-row img {
  width: 40px;
  height: 40px;
}

h1 {
  margin: 0;
  font-size: 20px;
}

.logo-row p,
.hint {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}

.hint {
  margin: 0 0 16px;
}

.err {
  margin: 12px 0 0;
  color: #b91c1c;
  font-size: 13px;
}

.mode {
  margin-top: 18px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}
</style>
