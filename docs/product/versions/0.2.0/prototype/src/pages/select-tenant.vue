<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>选择租户</h1>
          <p>{{ sessionAccount?.displayName }} · 产品原型 0.2.0</p>
        </div>
      </div>

      <p class="hint">
        {{
          isPlatformAdmin
            ? '这里只列出你已绑定且仍有效的租户。没有授权请回平台后台输入授权码。'
            : '选择要进入的租户。停用的租户不会出现。'
        }}
      </p>

      <a-input
        v-if="list.length > 6"
        v-model:value="keyword"
        allow-clear
        placeholder="搜索租户名称或编码"
        class="search"
      />

      <div v-if="!list.length" class="empty">没有可用租户。请联系平台管理员开通。</div>
      <div v-else-if="!filtered.length" class="empty">没有匹配的租户。</div>
      <div v-else class="tenants" :class="{ scroll: filtered.length > 6 }">
        <button
          v-for="t in filtered"
          :key="t.id"
          class="tenant"
          :class="{ on: selected === t.id, off: t.status === 'disabled' }"
          @click="selected = t.id"
        >
          <div class="name">
            {{ t.name }}
            <span v-if="t.id === currentId" class="now">当前</span>
          </div>
          <div class="meta">{{ t.code }} · {{ t.owner }}</div>
          <div class="meta">{{ count(t.id) }} 个项目 · {{ t.status === 'disabled' ? '已停用' : '使用中' }}</div>
        </button>
      </div>

      <a-button type="primary" size="large" block :disabled="!selected" @click="enter">进入租户</a-button>
      <a-button v-if="currentId" class="mt" size="large" block @click="cancel">返回</a-button>
      <a-button v-if="isPlatformAdmin" class="mt" size="large" block @click="router.push(ADMIN_HOME)">
        进入平台后台
      </a-button>
      <a-button class="mt" block @click="out">退出登录</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ADMIN_HOME } from '../config/paths';
import { app, isPlatformAdmin, logout, resolveTenantHome, selectableTenants, sessionAccount, switchTenant } from '../stores/app';

const router = useRouter();
const list = computed(() => selectableTenants());
const currentId = computed(() => app.currentTenantId);
const keyword = ref('');
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return list.value;
  return list.value.filter((t) => t.name.toLowerCase().includes(q) || t.code.toLowerCase().includes(q));
});
const selected = ref(
  list.value.find((t) => t.id === currentId.value)?.id ??
    list.value.find((t) => t.status === 'active')?.id ??
    list.value[0]?.id ??
    ''
);

function count(id: string) {
  return app.projects.filter((p) => p.tenantId === id).length;
}

function enter() {
  if (!switchTenant(selected.value)) return;
  router.push(resolveTenantHome());
}

function cancel() {
  const next = resolveTenantHome();
  router.push(next === '/app' || next === '/projects' || next === '/admin' || next === '/no-project' ? next : '/projects');
}

function out() {
  logout();
  router.push('/login');
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
  width: 560px;
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
.hint,
.empty,
.meta {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}

.hint {
  margin: 0 0 16px;
}

.search {
  margin-bottom: 12px;
}

.empty {
  margin-bottom: 16px;
}

.tenants {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 16px;
}

.tenants.scroll {
  max-height: 360px;
  overflow: auto;
  padding-right: 4px;
}

.tenant {
  text-align: left;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
}

.tenant.on {
  border-color: #0e7490;
  box-shadow: 0 0 0 3px rgba(14, 116, 144, 0.15);
}

.tenant.off {
  opacity: 0.65;
}

.name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-weight: 650;
}

.now {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  color: #0e7490;
}

.mt {
  margin-top: 10px;
}
</style>
