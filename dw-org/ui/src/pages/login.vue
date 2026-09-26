<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>登录 · 租户管理</h1>
          <p>多租户平台</p>
        </div>
      </div>

      <p class="hint">先认人，再选租户。租户管理员进工作台；其他用户直接进入已开通的产品页面。</p>

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
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, useRemoteApi } from '../api/client';
import { ADMIN_HOME, SELECT_TENANT } from '../config/paths';
import { followHome, openServiceReturn, serviceReturn } from '../config/product';
import { getDeployMode, setDeployModeHint } from '../config/runtime';
import { app, loginDev, loginLocal, navReady, resolveTenantHome, servicesReady } from '../stores/app';

const router = useRouter();
const route = useRoute();
const remote = useRemoteApi();
const username = ref('');
const password = ref('');
const busy = ref(false);
const err = ref('');

async function submit() {
  err.value = '';
  busy.value = true;
  try {
    const user = username.value.trim();
    const out = remote ? await loginDev(user, password.value) : loginLocal(user, password.value);

    // 「回哪」是别人弹我们来登录时带的参数：服务页面发现没身份 → 跳到这里并把原地址
    // 挂上（见 `config/product.ts` 的 `serviceReturn`）。没带、或带的是个不认识的地址
    // 时 back 为空，下面几条落点与以前一字不差。
    const back = serviceReturn(route.query.return);
    const rawReturn = typeof route.query.return === 'string' ? route.query.return : '';
    const needPick = Boolean(out.needSelectTenant || (out.tenants && out.tenants.length > 1));

    // 已经有租户上下文（单租户会在 loginDev 里自动选中）—— 直接带着身份回他要去的页面。
    // 这条刻意排在 platformAdmin 之前：平台管理员若绑了租户，一样该回得去。
    if (back && !needPick && app.currentTenantId) {
      // 两件事都得等，缺一个回跳就会静默失败：
      //   · `servicesReady()` —— `serviceReturn` 的白名单就是服务目录，没加载完时它是空的，
      //     于是那个 return 会被当成「不认识的地址」丢掉，用户落在门户首页（不报错）；
      //   · `navReady()` + `resolveTenantHome()` —— 后者除了算落点，还有把 projectId /
      //     projectCode 写进 sessionStorage 的副作用，而 `#boot=` 正是从那里读的；
      //     少了它，服务侧会当成「你还没有当前项目」，把用户打回项目概况。
      // `resolveTenantHome()` 的返回值这里不用（落点由 return 决定），只要那个副作用。
      await Promise.all([servicesReady(), navReady()]);
      resolveTenantHome();
      openServiceReturn(back);
      return;
    }
    if (out.platformAdmin && !back) {
      router.push(ADMIN_HOME);
      return;
    }
    // 选租户时把 return 一起带过去，选完再回跳（见 `pages/select-tenant.vue`）。
    // 平台管理员在没有租户上下文时也走这里 —— 他可能绑了租户；一个都没有的话，
    // 选租户页上本来就摆着「进入平台后台」。
    if (needPick || (back && !app.currentTenantId)) {
      router.push({ path: SELECT_TENANT, query: rawReturn ? { return: rawReturn } : {} });
      return;
    }
    // 落地页要看门户菜单（见 resolveTenantHome 的注释），等这次拉取落定再决定去哪
    await navReady();
    followHome(resolveTenantHome(), router);
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
    const mode = cfg.runMode || cfg.deployMode;
    if (mode && mode !== getDeployMode()) {
      setDeployModeHint(mode);
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
</style>
