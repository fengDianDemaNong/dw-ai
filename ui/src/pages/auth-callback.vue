<template>
  <div class="wrap">
    <p v-if="err">{{ err }}</p>
    <p v-else>正在完成 Casdoor 登录…</p>
    <a-button v-if="err" type="primary" @click="router.push('/login')">返回登录</a-button>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api, setAuthToken } from '../api/client';
import { exchangeCasdoorCode } from '../auth/casdoor';
import { bootstrapRemote } from '../stores/app';

const router = useRouter();
const err = ref('');

onMounted(async () => {
  const q = new URLSearchParams(window.location.search);
  const code = q.get('code');
  const state = q.get('state') ?? '';
  if (!code) {
    err.value = '回调缺少 code。请确认 Casdoor Application 的 Redirect URL 为 /auth/callback。';
    return;
  }
  try {
    const token = await exchangeCasdoorCode(code, state);
    setAuthToken(token);
    await api.me();
    await bootstrapRemote();
    router.replace('/projects');
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e);
  }
});
</script>

<style scoped>
.wrap {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 48px;
  color: #64748b;
}
</style>
