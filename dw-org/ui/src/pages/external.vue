<template>
  <div class="page ext">
    <PageHeader :title="title" :subtitle="host">
      <template #actions>
        <!--
          这不是「出错时才出现」的兜底，而是**常驻**的：目标站用 `X-Frame-Options` /
          CSP `frame-ancestors` 拒绝内嵌时，浏览器**不把失败暴露给 JS**（load 事件照样触发、
          contentDocument 跨域读不到），所以「探测到打不开再提示」这条路根本走不通。
          既然探测不到，就只能一开始就把出路摆在那里。
        -->
        <a v-if="url" :href="url" target="_blank" rel="noreferrer">
          <a-button>在新标签页打开</a-button>
        </a>
      </template>
    </PageHeader>

    <p v-if="loading" class="muted">正在加载…</p>

    <div v-else-if="error" class="card">
      <h3>这一页打不开</h3>
      <p class="muted">{{ error }}</p>
    </div>

    <iframe
      v-else-if="url"
      ref="frame"
      class="frame card"
      :src="url"
      :title="title"
      sandbox="allow-scripts allow-forms allow-same-origin allow-popups"
      referrerpolicy="no-referrer"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '../components/PageHeader.vue';
import { api } from '../api/client';

defineOptions({ name: 'NavExternalPage' });

/**
 * 一条外链菜单的内嵌页。
 *
 * <h2>为什么 sandbox 是这几个值</h2>
 *
 * <p>`allow-scripts` + `allow-same-origin` 在**跨域**场景下是安全的：给的权限是目标站
 * 自己 origin 的权限，同源策略照样拦住它读父页面。但不给的话目标站的 cookie /
 * localStorage 全废（登录态、主题、语言都记不住），外链菜单就失去了意义 ——
 * 这是**有意的取舍**，不是随手抄的。
 *
 * <p>刻意**不给** `allow-popups-to-escape-sandbox`（弹出去的窗口会脱离沙箱）、
 * 也不给 `allow-top-navigation`（目标站能把整个壳带走）。
 *
 * <p>`referrerpolicy="no-referrer"`：地址里带着 token，不能再让它经 Referer 泄给
 * 目标站引用的第三方资源。
 *
 * <h2>地址是**现场拉的**</h2>
 *
 * <p>`api.navExternal(id)` 由服务端把 token 拼好（见 `NavNodeService.externalTarget`）。
 * 不在侧栏树里给明文：那样每个页面的侧栏请求都会把凭据下发一遍。代价是这一页不能
 * 用 `NavItem.href`（那是裸地址），要自己拉一次。
 */
const route = useRoute();
const id = computed(() => String(route.params.id ?? ''));

const title = ref('外链页面');
const url = ref('');
const loading = ref(true);
const error = ref('');
const frame = ref<HTMLIFrameElement>();

/** 页头副标题：只显示主机名 —— 完整地址里有 token，不该铺在页头上。 */
const host = computed(() => {
  if (!url.value) return '';
  try {
    return new URL(url.value).host;
  } catch {
    return '';
  }
});

async function reload() {
  loading.value = true;
  error.value = '';
  try {
    const target = await api.navExternal(id.value);
    title.value = target.label || '外链页面';
    url.value = target.url;
  } catch (e) {
    url.value = '';
    // 403 是一种正常结局（这条外链只给租户管理员看），要说清是权限而不是「加载失败」。
    error.value = (e as { message?: string })?.message || '这一页不存在，或者你没有权限看它。';
  } finally {
    loading.value = false;
  }
}

onMounted(reload);
// 两条外链之间互跳时组件不会重新挂载，而 `id` 变了 —— 不监听会一直嵌着上一个站点。
watch(id, reload);
</script>

<style scoped>
.ext {
  display: flex;
  flex-direction: column;
  /* 页面本身不滚动：滚动交给 iframe 里的目标站，否则会出现两条滚动条。 */
  overflow: hidden;
}

.frame {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: 1px solid var(--line);
  /* 铺满剩下那一块 —— 内嵌页的意义就是「把外面的页面装进壳里」，留白只会让它变小。 */
  padding: 0;
}
</style>
