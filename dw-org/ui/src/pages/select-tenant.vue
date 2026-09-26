<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>选择租户</h1>
          <p>{{ sessionAccount?.displayName }}</p>
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
          <div class="meta">{{ t.status === 'disabled' ? '已停用' : '使用中' }}</div>
        </button>
      </div>

      <a-button type="primary" size="large" block :disabled="!selected" :loading="busy" @click="enter">
        进入租户
      </a-button>
      <a-button v-if="currentId" class="mt" size="large" block @click="cancel">返回</a-button>
      <a-button v-if="isPlatformAdmin" class="mt" size="large" block @click="router.push(ADMIN_HOME)">
        进入平台后台
      </a-button>
      <a-button class="mt" block @click="out">退出登录</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, useRemoteApi } from '../api/client';
import { ADMIN_HOME, LOGIN_PATH, NO_PROJECT, SYS_HOME } from '../config/paths';
import { followHome, openServiceReturn, serviceReturn } from '../config/product';
import {
  app,
  isPlatformAdmin,
  logout,
  navReady,
  resolveTenantHome,
  selectableTenants,
  servicesReady,
  sessionAccount,
  switchTenant,
} from '../stores/app';
import type { Tenant } from '../types';

const router = useRouter();
const route = useRoute();
const remote = useRemoteApi();
const remoteList = ref<Tenant[]>([]);
const keyword = ref('');
const list = computed(() => (remote && remoteList.value.length ? remoteList.value : selectableTenants()));
const currentId = computed(() => app.currentTenantId);
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return list.value;
  return list.value.filter((t) => t.name.toLowerCase().includes(q) || t.code.toLowerCase().includes(q));
});
const selected = ref('');
const busy = ref(false);

onMounted(async () => {
  if (remote) {
    try {
      remoteList.value = await api.listAuthTenants();
    } catch {
      remoteList.value = [];
    }
  }
  selected.value =
    list.value.find((t) => t.id === currentId.value)?.id ??
    list.value.find((t) => t.status !== 'disabled')?.id ??
    list.value[0]?.id ??
    '';
});

async function enter() {
  if (!selected.value) return;
  busy.value = true;
  try {
    if (!(await switchTenant(selected.value))) return;
    // 有人是「要回服务页面、但得先选租户」才来的（登录页把 return 一起带了过来）——
    // 租户定了就把他送回去，别再按本门户的落地页算。
    const back = serviceReturn(route.query.return);
    if (back) {
      // 与登录页同一套前戏，两件事都不能省（理由见 `pages/login.vue` 里那段注释）：
      // 服务目录决定 `serviceReturn` 的白名单，`resolveTenantHome()` 的副作用决定
      // `#boot=` 里的 projectId / projectCode。
      await Promise.all([servicesReady(), navReady()]);
      resolveTenantHome();
      openServiceReturn(back);
      return;
    }
    // 换租户后落地页可能变（另一个租户的许可下有别的产品页面），等新菜单到位再决定
    await navReady();
    followHome(resolveTenantHome(), router);
  } finally {
    busy.value = false;
  }
}

function cancel() {
  const next = resolveTenantHome();
  followHome(
    next === SYS_HOME || next === ADMIN_HOME || next === NO_PROJECT ? next : SYS_HOME,
    router
  );
}

function out() {
  logout();
  router.push(LOGIN_PATH);
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
