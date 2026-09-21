<template>
  <div class="page copilot">
    <PageHeader
      :title="`${layer} · AI 设计`"
      :subtitle="subtitle"
    >
      <template #actions>
        <a-button @click="router.push(layerHref(layer))">返回总览</a-button>
      </template>
    </PageHeader>
    <a-alert
      v-if="!hasModelAi"
      type="warning"
      show-icon
      class="cap-alert"
      message="本组织未开通此项"
      description="未开通建模 AI。请联系平台在仓建设下勾选「建模 AI」。"
    />
    <p v-else class="ctx">已带本项目规范：{{ modelContext }}</p>

    <div v-if="hasModelAi" class="split">
      <section class="chat">
        <div ref="listRef" class="msgs">
          <div v-if="!turns.length" class="empty">
            <p>用对话设计本层的表，或改已有表。确认后再写入系统。</p>
            <button v-for="s in starters" :key="s" type="button" class="chip" @click="send(s)">{{ s }}</button>
          </div>
          <div v-for="(t, i) in turns" :key="i" class="msg" :class="t.role">
            <div class="who">{{ t.role === 'user' ? '你' : '建模助手' }}</div>
            <div class="bubble">{{ t.text }}</div>
          </div>
          <div v-if="thinking" class="msg assistant">
            <div class="who">建模助手</div>
            <div class="bubble muted">正在按 {{ layer }} 层规范整理表结构…</div>
          </div>
        </div>
        <div class="composer">
          <a-textarea
            v-model:value="draft"
            :auto-size="{ minRows: 2, maxRows: 5 }"
            placeholder="描述要建的表、要加的字段，或对右侧草案提意见。Enter 发送"
            @keydown="onKey"
          />
          <a-button type="primary" :disabled="!draft.trim() || thinking" @click="send()">发送</a-button>
        </div>
      </section>

      <section class="draft-pane">
        <div v-if="!proposal" class="idle">对话生成草案后，这里勾选表，确认写入。新建为草稿；改已有表会记一版。</div>
        <template v-else>
          <div class="draft-head">
            <div>
              <b>{{ layer }} 表草案</b>
              <p class="muted">{{ proposal.summary }}</p>
            </div>
            <a-button
              v-if="canWriteModel"
              type="primary"
              :disabled="!picked.length"
              @click="sync"
            >
              生成草稿并写入系统
            </a-button>
          </div>
          <p v-if="!canWriteModel" class="muted">当前角色只能看草案，不能写入。</p>
          <label v-for="t in proposal.tables" :key="t.key" class="pick">
            <a-checkbox :checked="sel.includes(t.key)" @change="toggle(t.key)" />
            <div>
              <div>
                <a-tag :color="t.mode === 'create' ? 'gold' : 'blue'">{{ t.mode === 'create' ? '新建' : '修改' }}</a-tag>
                <code>{{ t.name }}</code>
                <span v-if="t.domain"> · {{ t.domain }}</span>
                <span v-if="t.grain"> · {{ t.grain }}</span>
              </div>
              <div class="muted">{{ t.comment }} · {{ t.summary }}</div>
              <div class="cols">
                <code v-for="c in t.columns" :key="c.name">{{ c.name }} {{ c.type }}</code>
              </div>
            </div>
          </label>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal } from 'ant-design-vue';
import { assessImpact } from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';
import { layerHref, parseLayerParam } from '../../config/layers';
import { modelStarters, replyModelChat, type ModelChatTurn } from '../../engine/modelChat';
import {
  app,
  applyModelProposal,
  canWriteModel,
  currentProject,
  hasAiCap,
  projectDomains,
  projectLayerRules,
  projectTables,
} from '../../stores/app';
import { LLM_PROVIDERS, llmOf } from '../../stores/prefs';
import type { ModelChatProposal } from '../../types';

const route = useRoute();
const router = useRouter();
const layer = computed(() => parseLayerParam(route.params.layer));
const tables = computed(() => projectTables.value.filter((t) => t.layer === layer.value));
const focus = computed(() => {
  const id = typeof route.query.table === 'string' ? route.query.table : '';
  return tables.value.find((t) => t.id === id) ?? null;
});
const starters = computed(() => modelStarters(layer.value, tables.value));
const llmHint = computed(() => {
  const c = llmOf(app.currentTenantId);
  if (!c.enabled) return '未开启租户大模型时用内置草案。';
  const name = LLM_PROVIDERS.find((p) => p.value === c.provider)?.label ?? c.provider;
  return `本租户已配置 ${name} / ${c.model || '未填模型'}，原型对话仍用内置草案演示。`;
});
const hasModelAi = computed(() => hasAiCap('model_design'));
const modelContext = computed(() => {
  const domains = projectDomains.value.map((d) => d.code).join('、') || '无';
  const rule = projectLayerRules.value.find((r) => r.layer === layer.value);
  const tablesHint = tables.value.length ? `${tables.value.length} 张已有表` : '尚无表';
  return `${currentProject.value?.name ?? '未选项目'} · 域 ${domains} · ${tablesHint}${rule ? ` · ${rule.naming}` : ''}`;
});
const subtitle = computed(() => {
  const focusName = focus.value ? `当前针对 ${focus.value.name}。` : '';
  return `用对话完成本层建模，已带本项目规范。${focusName}${llmHint.value}`;
});

const draft = ref('');
const thinking = ref(false);
const turns = ref<ModelChatTurn[]>([]);
const proposal = ref<ModelChatProposal | null>(null);
const sel = ref<string[]>([]);
const listRef = ref<HTMLElement>();
const storageKey = computed(
  () => `dw-ai.modelChat.${currentProject.value?.id ?? 'none'}.${layer.value}.${focus.value?.id ?? 'new'}`
);
const picked = computed(() => proposal.value?.tables.filter((t) => sel.value.includes(t.key)) ?? []);

function persistChat() {
  try {
    localStorage.setItem(storageKey.value, JSON.stringify({ turns: turns.value, proposal: proposal.value, sel: sel.value }));
  } catch {
    /* ignore */
  }
}

function loadChat() {
  try {
    const raw = localStorage.getItem(storageKey.value);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    turns.value = parsed.turns ?? [];
    proposal.value = parsed.proposal ?? null;
    sel.value = parsed.sel ?? [];
  } catch {
    /* ignore */
  }
}

function toggle(key: string) {
  const i = sel.value.indexOf(key);
  if (i >= 0) sel.value.splice(i, 1);
  else sel.value.push(key);
  persistChat();
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
}

async function send(text?: string) {
  const content = (text ?? draft.value).trim();
  if (!content || thinking.value || !hasModelAi.value) return;
  draft.value = '';
  turns.value.push({ role: 'user', text: content });
  thinking.value = true;
  await scroll();
  await new Promise((r) => setTimeout(r, 400));
  const ctx = {
    layer: layer.value,
    tables: tables.value,
    domains: projectDomains.value.map((d) => ({ code: d.code, name: d.name })),
    focusTable: focus.value,
  };
  const reply = replyModelChat(content, ctx, proposal.value);
  proposal.value = reply.proposal;
  sel.value = reply.proposal.tables.map((t) => t.key);
  turns.value.push({ role: 'assistant', text: reply.text });
  thinking.value = false;
  persistChat();
  await scroll();
}

async function scroll() {
  await nextTick();
  const el = listRef.value;
  if (el) el.scrollTop = el.scrollHeight;
}

function sync() {
  if (!picked.value.length) return;
  const lines = [`将采纳 ${picked.value.length} 张表。新建为草稿；修改已有表会生成新版本。`];
  for (const d of picked.value) {
    if (d.mode !== 'update' || !d.tableId) continue;
    const cur = projectTables.value.find((t) => t.id === d.tableId);
    if (!cur) continue;
    const report = assessImpact(cur, { ...cur, ...d, columns: d.columns }, projectTables.value);
    if (report.needConfirm) lines.push(...report.lines.map((l) => `${d.name}：${l}`));
  }
  Modal.confirm({
    title: '写入当前项目？',
    content: lines.join('\n'),
    okText: '确认写入（不改下游）',
    onOk() {
      const n = applyModelProposal(picked.value);
      if (n) router.push(layerHref(layer.value));
    },
  });
}

watch(storageKey, () => {
  turns.value = [];
  proposal.value = null;
  sel.value = [];
  loadChat();
});

onMounted(() => {
  loadChat();
  scroll();
});
</script>

<style scoped>
.copilot {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding-bottom: 16px;
}
.cap-alert {
  margin-bottom: 12px;
}
.ctx {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--muted);
}
.split {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(380px, 1.15fr);
  gap: 12px;
}
.chat,
.draft-pane {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 10px;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.msgs {
  flex: 1;
  overflow: auto;
  padding: 16px;
}
.empty p {
  color: var(--muted);
  margin: 0 0 10px;
}
.chip {
  display: block;
  width: 100%;
  text-align: left;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--page);
  cursor: pointer;
  font-size: 13px;
  color: inherit;
}
.chip:hover {
  border-color: var(--primary);
}
.msg {
  margin-bottom: 12px;
}
.who {
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}
.bubble {
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--page);
  max-width: 92%;
}
.msg.user {
  text-align: right;
}
.msg.user .bubble {
  margin-left: auto;
  background: #ecfeff;
  color: #134e4a;
  text-align: left;
}
.composer {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--line);
  align-items: flex-end;
}
.composer :deep(textarea) {
  flex: 1;
}
.draft-pane {
  overflow: auto;
  padding: 14px 16px 20px;
}
.idle {
  color: var(--muted);
  font-size: 13px;
  padding: 24px 8px;
}
.draft-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}
.draft-head p {
  margin: 4px 0 0;
  font-size: 12px;
}
.pick {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 0;
  border-top: 1px dashed var(--line);
  cursor: pointer;
}
.pick code {
  font-size: 12px;
}
.cols {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.cols code {
  background: var(--page);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
