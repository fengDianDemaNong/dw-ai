<template>
  <div class="login">
    <div class="panel">
      <div class="brand">
        <img src="/logo.png" alt="" />
        <div>
          <h1>数据地图</h1>
          <p>{{ subtitle }}</p>
        </div>
      </div>

      <a-form layout="vertical" @submit.prevent="submit">
        <a-form-item label="用户名">
          <a-input
            v-model:value="username"
            size="large"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="busy"
            @pressEnter="submit"
          />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password
            v-model:value="password"
            size="large"
            placeholder="请输入密码"
            autocomplete="current-password"
            :disabled="busy"
            @pressEnter="submit"
          />
        </a-form-item>
        <a-button type="primary" size="large" block :loading="busy" @click="submit">登录</a-button>
      </a-form>

      <a-alert v-if="err" class="err" type="error" :message="err" show-icon />

      <p class="foot">{{ footNote }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { defaultHome } from '../config/pages';
import { isMulti, isStandalone, orgOrigin } from '../config/runtime';
import { hasSession, login } from '../stores/auth';

/**
 * standard（普通模式）的本地登录页。
 *
 * <h2>三种模式各自的归宿</h2>
 *
 * <p>这一页<strong>只为 standard 而画</strong>。另外两种模式进来会立刻被送走：
 * standalone 没有账号体系（`/api/**` 全放行），multi 的身份由组织平台负责。
 * 路由守卫已经拦了大部分情况，这里的 onMounted 是第二道 —— 直接输 URL 打开
 * 这一页时守卫也会跑，但多一层明确的兜底比「依赖守卫一定没被绕过」更让人放心。
 *
 * <h2>为什么不用 a-form 的校验规则</h2>
 *
 * <p>用户名/密码「填没填」后端一句话就能判断，而真正会失败的是凭据不对 ——
 * 那要靠一次真实请求才知道。为不可能发生的本地校验写一堆 rules，
 * 反而会让「点了没反应」和「凭据错」看起来一样。
 */
const router = useRouter();
const route = useRoute();

const username = ref('');
const password = ref('');
const busy = ref(false);
const err = ref('');

const subtitle = computed(() => '普通模式 · 本地账号');
const footNote = computed(() =>
  '首次部署会自动创建一个管理员账号，凭据见后端启动日志。忘记密码可由其他管理员重置。'
);

/**
 * 登录成功后的落点。
 *
 * <p>没带 `redirect` 就是「直接来的登录页」，落到本部署的首页 ——
 * standard 下那是**工作台**（先看全部项目，再点进某一个），不是项目概况。
 * 守卫在拦截时也会刻意不给首页目标加 `redirect`，两边算的是同一个地址。
 *
 * <p>只接受站内路径。`//evil.com` 这种协议相对地址必须以 `//` 挡掉 ——
 * 它是「以 / 开头」的，只判开头会被放行成一个跳转到外站的开链。
 */
function safeRedirect(): string {
  const q = route.query.redirect;
  const raw = typeof q === 'string' ? q : '';
  if (!raw.startsWith('/') || raw.startsWith('//')) return defaultHome();
  return raw;
}

async function submit() {
  if (busy.value) return;
  err.value = '';
  const user = username.value.trim();
  if (!user || !password.value) {
    err.value = '请填写用户名和密码';
    return;
  }
  busy.value = true;
  try {
    await login(user, password.value);
    // 用 replace 而不是 push：登录页不该留在后退栈里 ——
    // 否则登出后按一下后退又回到登录页，看着像「登出失败」
    await router.replace(safeRedirect());
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败，请稍后重试';
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  if (isStandalone()) {
    void router.replace(defaultHome());
    return;
  }
  if (isMulti()) {
    // 整页跳去组织平台，不用 router：那是另一个前端，不属这个路由表
    window.location.assign(`${orgOrigin()}/org/login`);
    return;
  }
  // 已经有会话了就不用再登一次（守卫一般已经处理，这里防的是直接打开本页）
  if (hasSession()) void router.replace(safeRedirect());
});
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  /* 与应用其它页面同一套浅色底，避免从登录页进来时有一次「换了个产品」的错觉 */
  background:
    radial-gradient(900px 420px at 50% 0%, rgba(22, 119, 255, 0.1), transparent),
    #f5f6f8;
}

.panel {
  width: 400px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.brand img {
  width: 40px;
  height: 40px;
  border-radius: 8px;
}

.brand h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.3;
  color: #111827;
}

.brand p {
  margin: 2px 0 0;
  font-size: 13px;
  color: #6b7280;
}

.err {
  margin-top: 16px;
}

.foot {
  margin: 20px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #9ca3af;
}
</style>
