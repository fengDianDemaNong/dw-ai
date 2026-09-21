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

const MSG_NAV = 'dw-embed-navigate';
const MSG_READY = 'dw-embed-ready';

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
    return [props.product, u.origin, boot.tenantCode, boot.projectCode, boot.token].join('|');
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
  if (e.data?.type !== MSG_READY) return;
  try {
    if (frameSrc.value && e.origin !== new URL(frameSrc.value).origin) return;
  } catch {
    return;
  }
  ready.value = true;
  flushPending();
}

watch(
  () => props.src,
  (next) => applySrc(next),
  { immediate: true }
);

onMounted(() => window.addEventListener('message', onMessage));
onUnmounted(() => window.removeEventListener('message', onMessage));
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
