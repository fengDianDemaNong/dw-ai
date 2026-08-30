<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>智仓 DW-AI · 产品原型 0.1.3</h1>
          <p>用户名密码登录。当前启动：{{ modeLabel }}</p>
        </div>
      </div>

      <div class="where">
        请确认地址栏是 <b>127.0.0.1:4203</b>。4183 是 0.1.1，4173 是 0.1.0，5173 是实现窗口。
      </div>
      <p class="hint">{{ hint }}</p>

      <a-form layout="vertical" @submit.prevent="submit">
        <a-form-item label="用户名">
          <a-input v-model:value="username" placeholder="admin / 张三 / 李四" @pressEnter="submit" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="password" placeholder="密码" @pressEnter="submit" />
        </a-form-item>
        <a-button type="primary" size="large" block :loading="busy" @click="submit">登录</a-button>
      </a-form>
      <p v-if="err" class="err">{{ err }}</p>

      <div class="demos">
        <div v-for="d in demos" :key="d.username" class="demo" @click="fill(d.username, d.password)">
          <b>{{ d.username }}</b>
          <span>{{ d.password }}</span>
          <em>{{ d.note }}</em>
        </div>
      </div>

      <div class="mode">
        <span>启动模式（改完刷新，等同重启服务）</span>
        <a-radio-group :value="mode" size="small" @change="onMode">
          <a-radio-button value="multi">多租户</a-radio-button>
          <a-radio-button value="standard">普通</a-radio-button>
        </a-radio-group>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { DEMO_ACCOUNTS_MULTI, DEMO_ACCOUNTS_STANDARD } from '../config/iam';
import { ADMIN_HOME } from '../config/paths';
import { getDeployMode, isMultiTenant, setDeployMode, type DeployMode } from '../config/runtime';
import { login, resolveTenantHome } from '../stores/app';

const router = useRouter();
const username = ref('');
const password = ref('');
const busy = ref(false);
const err = ref('');
const mode = getDeployMode();
const modeLabel = mode === 'multi' ? '多租户' : '普通模式';
const hint = isMultiTenant()
  ? '先认人，再选租户。租户管理员进工作台；其他用户直接进已加入的项目。'
  : '普通模式：没有平台后台和租户。管理员进工作台，其他人进项目。';
const demos = computed(() => (isMultiTenant() ? DEMO_ACCOUNTS_MULTI : DEMO_ACCOUNTS_STANDARD));

function onMode(e: { target: { value: string | number | boolean } }) {
  setDeployMode(e.target.value as DeployMode);
}

function fill(u: string, p: string) {
  username.value = u;
  password.value = p;
  err.value = '';
}

function submit() {
  err.value = '';
  busy.value = true;
  try {
    const { account, tenants } = login(username.value, password.value);
    if (!isMultiTenant()) {
      router.push(resolveTenantHome());
      return;
    }
    if (account.platformAdmin) {
      router.push(ADMIN_HOME);
      return;
    }
    if (tenants.length === 1) {
      router.push(resolveTenantHome());
      return;
    }
    router.push('/select-tenant');
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}
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

.where {
  margin: 0 0 14px;
  padding: 10px 12px;
  background: #ecfeff;
  border: 1px solid #67e8f9;
  border-radius: 8px;
  color: #155e75;
  font-size: 13px;
  line-height: 1.5;
}

.err {
  margin: 12px 0 0;
  color: #b91c1c;
  font-size: 13px;
}

.demos {
  margin-top: 18px;
  display: grid;
  gap: 8px;
}

.demo {
  display: grid;
  grid-template-columns: 72px 88px 1fr;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px dashed #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
}

.demo:hover {
  border-color: #0e7490;
}

.demo b {
  color: #0f172a;
}

.demo em {
  font-style: normal;
  color: #94a3b8;
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
