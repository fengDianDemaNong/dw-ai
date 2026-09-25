<template>
  <div class="page">
    <div v-if="!settled" class="muted pad">正在加载…</div>

    <div v-else class="card pad">
      <h3>这个项目还没有配置页面</h3>
      <p class="muted">
        项目壳把各服务的菜单挂进来。现在一条都没有 —— 到
        <router-link :to="ORG_PAGES.platformServices">服务注册</router-link>
        确认各服务的页面地址已登记，再到
        <router-link :to="ORG_PAGES.platformNav">菜单管理</router-link>
        「从服务拉取菜单」，把要挂到<b>项目壳</b>的那些勾上。
      </p>
      <p class="muted">
        也可以先用左侧的「返回工作台」回去。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ORG_PAGES } from '../config/pages';
import { loadNav, navReady, projectMenuHref } from '../stores/app';

/**
 * 项目壳的首页（`/org/project/{code}`）。
 *
 * <p>有可嵌的项目菜单就直接进第一条 —— 「进入项目」按钮落在这一页，用户不该先看到
 * 一个中转页。一条都没有时<b>留在原地说明原因</b>，而不是弹回工作台：弹回会让
 * 刚点了「进入项目」的人以为自己点错了，而真正缺的是管理员还没配菜单。
 */
const route = useRoute();
const router = useRouter();
const settled = ref(false);

onMounted(async () => {
  // 直接刷新这一页时菜单可能还没拉过（`bootstrapRemote` 拉过一次，但换租户/换项目后会重拉）
  await loadNav();
  await navReady();
  const code = String(route.params.code ?? '');
  const href = projectMenuHref(code);
  if (href) {
    await router.replace(href);
    return;
  }
  settled.value = true;
});
</script>

<style scoped>
.pad {
  margin: 16px;
}
</style>
