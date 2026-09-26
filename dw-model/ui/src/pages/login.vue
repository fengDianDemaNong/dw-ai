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
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api, useRemoteApi } from '../api/client';
import { ADMIN_HOME, MODEL_HOME } from '../config/paths';
import { openOrgLogin } from '../config/product';
import { getDeployMode, isMultiTenant, isStandalone, setDeployModeHint } from '../config/runtime';
import { ensureProjectSnapshot, loginDev, loginLocal, resolveTenantHome } from '../stores/app';

const router = useRouter();
const remote = useRemoteApi();
const username = ref('');
const password = ref('');
const busy = ref(false);
const err = ref('');
const modeLabel = computed(() => (isMultiTenant() ? '多租户' : '普通模式'));
const hint = computed(() =>
  isMultiTenant()
    ? '多租户请从组织平台登录。'
    : '普通模式：本进程本地账号，没有平台后台。管理员进项目管理，其他人进项目。'
);

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
      if (next === MODEL_HOME) await ensureProjectSnapshot();
      router.push(next);
      return;
    }
    if (out.needSelectTenant || (out.tenants && out.tenants.length > 1)) {
      openOrgLogin();
      return;
    }
    const next = resolveTenantHome();
    if (next === MODEL_HOME) await ensureProjectSnapshot();
    router.push(next);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '用户名或密码错误';
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  if (isStandalone()) {
    // 正常到不了这里（路由守卫先把 standalone 弹回本模式首页），留着是兜底。
    // 落点交给 `resolveTenantHome()` 而不是写死 MODEL_HOME —— standalone 也有工作台，
    // 管理员在没有当前项目时该看到的是工作台，不是项目概况。
    router.replace(resolveTenantHome());
    return;
  }
  if (isMultiTenant()) {
    // 正常到不了这里：守卫在进本页之前就把 multi 的无身份访问送去组织平台了
    // （`stores/app.ts` 的 bootstrapRemote 不再把 multi 误判成 standalone 之后，
    // 首屏也只跳一次）。留着是兜底。
    //
    // **不带 return**：本页是「登录页」，用户没有「本来要去的地方」——
    // 带着 `/model/login` 回跳只会绕一圈又回到这里。真正该带的是守卫手上那个
    // `to.fullPath`，它比本页早一步知道用户想去哪。
    openOrgLogin();
    return;
  }
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
