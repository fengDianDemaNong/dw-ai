<template>
  <div class="embed">
    <iframe
      v-if="frameSrc"
      :key="session"
      ref="frame"
      class="pane"
      :src="frameSrc"
      :title="label"
      @load="onLoad"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { AUTH_TOKEN_EVENT, authToken, storedTokenExp } from '../api/client';

const MSG_NAV = 'dw-embed-navigate';
const MSG_READY = 'dw-embed-ready';
const MSG_TOKEN = 'dw-embed-token';
/** 子应用发现 token 失效（401）时向宿主求新 token。 */
const MSG_TOKEN_REQUEST = 'dw-embed-token-request';

const props = defineProps<{
  product: string;
  label: string;
  src: string;
}>();

const frame = ref<HTMLIFrameElement>();
const frameSrc = ref('');
const session = ref('');
const ready = ref(false);
const pending = ref<string | null>(null);

/**
 * iframe 的「会话身份」：哪些变化必须重建 iframe。
 *
 * <p>刻意**不含 access token**。token 有 15 分钟有效期，把它算进会话身份意味着
 * 每次续期都要重建 iframe —— 子应用（数据地图）的页面状态、正在编辑的 SQL 全丢。
 * token 变化走 `dw-embed-token` 消息通道原地换掉，见 {@link postToken}。
 *
 * <p>租户/项目仍在里面：切换租户是真正的会话切换，重建是对的。
 */
function sessionKey(src: string): string {
  try {
    const u = new URL(src);
    const raw = u.hash.startsWith('#boot=') ? u.hash.slice(6) : '';
    if (!raw) return `${props.product}|${u.origin}`;
    const boot = JSON.parse(decodeURIComponent(raw)) as {
      tenantCode?: string;
      projectCode?: string;
      token?: string;
    };
    return [props.product, u.origin, boot.tenantCode, boot.projectCode].join('|');
  } catch {
    return `${props.product}|${src}`;
  }
}

function childTarget(src: string): { path: string; query: Record<string, string> } {
  const u = new URL(src);
  const query = Object.fromEntries(u.searchParams);
  delete query.embed;
  return { path: u.pathname, query };
}

function postNavigate(src: string) {
  const origin = new URL(src).origin;
  const target = childTarget(src);
  frame.value?.contentWindow?.postMessage({ type: MSG_NAV, ...target }, origin);
}

/**
 * 把当前的 access token 推给子应用（原地续期，不重建 iframe）。
 *
 * <p>子应用（数据地图）在 multi 模式下每个 `/api/**` 请求都要带组织的 JWT，
 * 而 token 只有 15 分钟。没有这条通道时，token 一过期只能靠重建 iframe 兜住 ——
 * 代价是把用户正在编辑的东西全部丢掉。
 *
 * <p>两个触发点：token 变了（{@code dw-ai-token-changed}），以及子应用说自己 ready。
 * 后者是必要的：token 可能在 iframe 创建之后、ready 之前就换过一次，
 * 那次推送没人接；ready 时补一次，保证子应用手里的 token 一定是最新的。
 *
 * <p>目标 origin 取自 iframe 的 src，不用 {@code '*'} —— postMessage 带 token，
 * 广播出去等于把令牌交给任何一个恰好在监听的窗口。
 */
function postToken() {
  const win = frame.value?.contentWindow;
  if (!win || !frameSrc.value) return;
  let origin: string;
  try {
    origin = new URL(frameSrc.value).origin;
  } catch {
    return;
  }
  win.postMessage(
    {
      type: MSG_TOKEN,
      token: authToken(),
      tokenExp: storedTokenExp(),
    },
    origin
  );
}

function flushPending() {
  if (!pending.value || !ready.value) return;
  postNavigate(pending.value);
  pending.value = null;
}

function applySrc(next: string) {
  if (!next) return;
  const nextSession = sessionKey(next);
  if (!frameSrc.value) {
    frameSrc.value = next;
    session.value = nextSession;
    return;
  }
  if (nextSession !== session.value) {
    session.value = nextSession;
    frameSrc.value = next;
    ready.value = false;
    pending.value = null;
    return;
  }
  if (ready.value) {
    postNavigate(next);
    return;
  }
  pending.value = next;
}

function onLoad() {
  window.setTimeout(() => {
    if (ready.value) return;
    ready.value = true;
    flushPending();
  }, 0);
}

function onMessage(e: MessageEvent) {
  // 所有来自子应用的消息先过 origin 校验，再分发 —— 求令牌的请求更不能放过校验
  try {
    if (frameSrc.value && e.origin !== new URL(frameSrc.value).origin) return;
  } catch {
    return;
  }
  if (e.data?.type === MSG_TOKEN_REQUEST) {
    // 子应用的 token 失效了：宿主可能错过续期推送，或 iframe 加载晚于最近一次续期
    postToken();
    return;
  }
  if (e.data?.type !== MSG_READY) return;
  ready.value = true;
  flushPending();
  postToken();
}

watch(
  () => props.src,
  (next) => applySrc(next),
  { immediate: true }
);

onMounted(() => {
  window.addEventListener('message', onMessage);
  // token 续期：原地换掉子应用手里的令牌，不重建 iframe（见 sessionKey 的注释）
  window.addEventListener(AUTH_TOKEN_EVENT, postToken);
});
onUnmounted(() => {
  window.removeEventListener('message', onMessage);
  window.removeEventListener(AUTH_TOKEN_EVENT, postToken);
});
</script>

<style scoped>
.embed {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pane {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: 0;
  background: #fff;
}
</style>
