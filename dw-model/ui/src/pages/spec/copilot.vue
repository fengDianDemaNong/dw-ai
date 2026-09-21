<template>
  <div class="page copilot">
    <PageHeader :title="pageTitle" :subtitle="pageSubtitle">
      <template v-if="canSwitchMode" #actions>
        <a-radio-group v-model:value="mode" size="small" button-style="solid">
          <a-radio-button value="design">设计</a-radio-button>
          <a-radio-button value="ask">问答</a-radio-button>
        </a-radio-group>
      </template>
    </PageHeader>
    <a-alert
      v-if="!canUseSpecAi"
      type="warning"
      show-icon
      class="cap-alert"
      message="本组织未开通此项"
      description="未开通规范设计或规范问答。请联系平台开通仓建设下的 AI 能力。"
    />
    <SpecReadonlyTip v-else-if="isDesign" />
    <p v-if="canUseSpecAi" class="ctx">已带本项目规范：{{ specContext }}</p>

    <div v-if="canUseSpecAi" class="split" :class="{ ask: isAsk }">
      <section class="chat">
        <div ref="listRef" class="msgs">
          <div v-if="!turns.length" class="empty">
            <p>先说清楚业务过程，不要说部门名。例如：</p>
            <button v-for="s in starters" :key="s" type="button" class="chip" @click="send(s)">{{ s }}</button>
          </div>
          <div v-for="(t, i) in turns" :key="i" class="msg" :class="t.role">
            <div class="who">{{ t.role === 'user' ? '你' : '规范助手' }}</div>
            <div class="bubble">{{ t.text }}</div>
          </div>
          <div v-if="thinking" class="msg assistant">
            <div class="who">规范助手</div>
            <div class="bubble muted">正在按业务过程划分主题域、分层、数据等级和词根…</div>
          </div>
        </div>
        <div class="composer">
          <a-textarea
            v-model:value="draft"
            :auto-size="{ minRows: 2, maxRows: 5 }"
            placeholder="描述业务，或对右侧草案提意见。Enter 发送，Shift+Enter 换行"
            @keydown="onKey"
          />
          <a-button type="primary" :disabled="!draft.trim() || thinking" @click="send()">发送</a-button>
        </div>
      </section>

      <section v-if="isDesign" class="draft-pane">
        <div v-if="!proposal" class="idle">
          对话生成草案后，这里可以勾选条目，确认后写入主题域、分层规范、数据等级、词根库。默认增量补全；覆盖须勾选「已存在则覆盖」。
        </div>
        <template v-else>
          <div class="draft-head">
            <div>
              <b>{{ proposal.industryLabel }} 规范草案</b>
              <p class="muted">{{ proposal.summary }}</p>
            </div>
            <a-space v-if="canWrite">
              <a-checkbox v-model:checked="overwrite">已存在则覆盖</a-checkbox>
              <a-button type="primary" :disabled="!selectedCount" @click="sync">确认同步进系统</a-button>
            </a-space>
          </div>

          <div class="block">
            <div class="block-h">
              <a-checkbox
                :checked="allDomainsOn"
                :indeterminate="someDomains"
                @change="onAllDomains"
              >
                主题域（{{ pickedDomains.length }}/{{ proposal.domains.length }}）
              </a-checkbox>
            </div>
            <label v-for="d in proposal.domains" :key="d.code" class="pick">
              <a-checkbox :checked="sel.domains.includes(d.code)" @change="toggle('domains', d.code)" />
              <div>
                <div><code>{{ d.code }}</code> {{ d.name }}</div>
                <div class="muted">{{ d.definition }}</div>
                <div class="muted">核心实体：{{ d.coreEntities.join('、') }} · 关联 {{ d.related.join('、') || '—' }}</div>
              </div>
            </label>
          </div>

          <div class="block">
            <div class="block-h">
              <a-checkbox
                :checked="allLayersOn"
                :indeterminate="someLayers"
                @change="onAllLayers"
              >
                分层（{{ pickedLayers.length }}/{{ proposal.layers.length }}）
              </a-checkbox>
            </div>
            <label v-for="l in proposal.layers" :key="l.layer" class="pick">
              <a-checkbox :checked="sel.layers.includes(l.layer)" @change="toggle('layers', l.layer)" />
              <div>
                <div>{{ l.layer }} · 保留 {{ l.retention }} · {{ serveLabel[l.serve] }}</div>
                <div class="muted"><code>{{ l.naming }}</code> {{ l.note }}</div>
              </div>
            </label>
          </div>

          <div class="block">
            <div class="block-h">
              <a-checkbox
                :checked="allGradesOn"
                :indeterminate="someGrades"
                @change="onAllGrades"
              >
                数据等级（{{ pickedGrades.length }}/{{ (proposal.grades ?? []).length }}）
              </a-checkbox>
            </div>
            <label v-for="g in proposal.grades ?? []" :key="g.code" class="pick">
              <a-checkbox :checked="sel.grades.includes(g.code)" @change="toggle('grades', g.code)" />
              <div>
                <div><code>{{ g.code }}</code> {{ g.name }} · 查询{{ queryLabel(g.query) }} · 导出{{ exportLabel(g.export) }}</div>
                <div class="muted">{{ g.note }}</div>
              </div>
            </label>
          </div>

          <div class="block">
            <div class="block-h">
              <a-checkbox
                :checked="allRootsOn"
                :indeterminate="someRoots"
                @change="onAllRoots"
              >
                词根（{{ pickedRoots.length }}/{{ proposal.roots.length }}）
              </a-checkbox>
              <span class="muted field">字段命名 {{ proposal.fieldNaming }}</span>
            </div>
            <div class="roots">
              <label v-for="r in proposal.roots" :key="r.code" class="root">
                <a-checkbox :checked="sel.roots.includes(r.code)" @change="toggle('roots', r.code)" />
                <span><code>{{ r.code }}</code> {{ r.zh }}</span>
                <a-tag>{{ r.kind }}</a-tag>
              </label>
            </div>
          </div>
        </template>
      </section>
      <section v-else class="draft-pane idle-ask">
        问答只解释当前项目已有约定，不生成可同步草案。
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { api, useRemoteApi } from '../../api/client';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import { replySpecChat, SPEC_STARTERS, type SpecChatTurn } from '../../engine/specChat';
import { exportLabel, gradesForIndustry, queryLabel } from '../../config/grades';
import {
  applySpecProposal,
  can,
  canWriteSpec,
  currentProject,
  hasAiCap,
  projectDomains,
  projectGrades,
  projectLayerRules,
  projectRoots,
} from '../../stores/app';
import type { SpecProposal } from '../../types';

const router = useRouter();
const canWrite = computed(() => can('spec:write'));
const canDesign = computed(() => hasAiCap('spec_design') && canWriteSpec.value);
const canAsk = computed(() => hasAiCap('spec_ask'));
const canUseSpecAi = computed(() => hasAiCap('spec_design') || hasAiCap('spec_ask'));
const canSwitchMode = computed(() => canDesign.value && canAsk.value);
const mode = ref<'design' | 'ask'>(canDesign.value ? 'design' : 'ask');
watch([canDesign, canAsk], () => {
  if (!canDesign.value && canAsk.value) mode.value = 'ask';
  if (canDesign.value && !canAsk.value) mode.value = 'design';
});
const isDesign = computed(() => mode.value === 'design' && canDesign.value);
const isAsk = computed(() => !isDesign.value && canUseSpecAi.value);
const pageTitle = computed(() => (isAsk.value ? '规范问答' : 'AI 设计规范'));
const pageSubtitle = computed(() =>
  isAsk.value
    ? '只解释本项目已有约定，不写入规范。需要读规范权限。'
    : '用对话描述业务，生成主题域 / 分层 / 数据等级 / 词根草案。默认增量补全，项目管理员可勾选后同步。'
);
const specContext = computed(() => {
  const p = currentProject.value;
  return `${p?.name ?? '未选项目'} · 主题域 ${projectDomains.value.length} · 分层 ${projectLayerRules.value.length} · 等级 ${projectGrades.value.length} · 词根 ${projectRoots.value.length}`;
});
const starters = SPEC_STARTERS;
const draft = ref('');
const thinking = ref(false);
const overwrite = ref(false);
const turns = ref<SpecChatTurn[]>([]);
const proposal = ref<SpecProposal | null>(null);
const listRef = ref<HTMLElement>();
const sel = reactive({ domains: [] as string[], layers: [] as string[], roots: [] as string[], grades: [] as string[] });

const serveLabel = { forbid: '禁止对外', approval: '审批后', allow: '允许' };

const storageKey = computed(() => `dw-ai.specChat.${currentProject.value?.id ?? 'none'}`);

function persistChat() {
  try {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({ turns: turns.value, proposal: proposal.value, sel, overwrite: overwrite.value })
    );
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
    overwrite.value = Boolean(parsed.overwrite);
    if (parsed.sel) {
      sel.domains = parsed.sel.domains ?? [];
      sel.layers = parsed.sel.layers ?? [];
      sel.roots = parsed.sel.roots ?? [];
      sel.grades = parsed.sel.grades ?? [];
    }
    if (proposal.value && !proposal.value.grades?.length) {
      proposal.value.grades = gradesForIndustry(proposal.value.industry);
      if (!sel.grades.length) sel.grades = proposal.value.grades.map((g) => g.code);
    }
  } catch {
    /* ignore */
  }
}

function selectAll(p: SpecProposal) {
  sel.domains = p.domains.map((d) => d.code);
  sel.layers = p.layers.map((l) => l.layer);
  sel.roots = p.roots.map((r) => r.code);
  sel.grades = (p.grades ?? []).map((g) => g.code);
}

function mergeSelection(p: SpecProposal) {
  const oldCodes = new Set(proposal.value?.domains.map((d) => d.code) ?? []);
  sel.domains = p.domains.map((d) => d.code).filter((c) => sel.domains.includes(c) || !oldCodes.has(c));
  const oldL = new Set(proposal.value?.layers.map((l) => l.layer) ?? []);
  sel.layers = p.layers.map((l) => l.layer).filter((c) => sel.layers.includes(c) || !oldL.has(c));
  const oldR = new Set(proposal.value?.roots.map((r) => r.code) ?? []);
  sel.roots = p.roots.map((r) => r.code).filter((c) => sel.roots.includes(c) || !oldR.has(c));
  const oldG = new Set(proposal.value?.grades?.map((g) => g.code) ?? []);
  sel.grades = (p.grades ?? []).map((g) => g.code).filter((c) => sel.grades.includes(c) || !oldG.has(c));
}

const pickedDomains = computed(() => proposal.value?.domains.filter((d) => sel.domains.includes(d.code)) ?? []);
const pickedLayers = computed(() => proposal.value?.layers.filter((l) => sel.layers.includes(l.layer)) ?? []);
const pickedRoots = computed(() => proposal.value?.roots.filter((r) => sel.roots.includes(r.code)) ?? []);
const pickedGrades = computed(() => (proposal.value?.grades ?? []).filter((g) => sel.grades.includes(g.code)));
const selectedCount = computed(
  () => pickedDomains.value.length + pickedLayers.value.length + pickedRoots.value.length + pickedGrades.value.length
);
const allDomainsOn = computed(() => !!proposal.value && sel.domains.length === proposal.value.domains.length);
const allLayersOn = computed(() => !!proposal.value && sel.layers.length === proposal.value.layers.length);
const allRootsOn = computed(() => !!proposal.value && sel.roots.length === proposal.value.roots.length);
const allGradesOn = computed(
  () => !!proposal.value && (proposal.value.grades ?? []).length > 0 && sel.grades.length === (proposal.value.grades ?? []).length
);
const someDomains = computed(() => sel.domains.length > 0 && !allDomainsOn.value);
const someLayers = computed(() => sel.layers.length > 0 && !allLayersOn.value);
const someRoots = computed(() => sel.roots.length > 0 && !allRootsOn.value);
const someGrades = computed(() => sel.grades.length > 0 && !allGradesOn.value);

function toggle(kind: 'domains' | 'layers' | 'roots' | 'grades', id: string) {
  const arr = sel[kind] as string[];
  const i = arr.indexOf(id);
  if (i >= 0) arr.splice(i, 1);
  else arr.push(id);
  persistChat();
}

function toggleAll(kind: 'domain' | 'layer' | 'root' | 'grade', on: boolean) {
  if (!proposal.value) return;
  if (kind === 'domain') sel.domains = on ? proposal.value.domains.map((d) => d.code) : [];
  if (kind === 'layer') sel.layers = on ? proposal.value.layers.map((l) => l.layer) : [];
  if (kind === 'root') sel.roots = on ? proposal.value.roots.map((r) => r.code) : [];
  if (kind === 'grade') sel.grades = on ? (proposal.value.grades ?? []).map((g) => g.code) : [];
  persistChat();
}

function onAllDomains(e: { target: { checked: boolean } }) {
  toggleAll('domain', e.target.checked);
}
function onAllLayers(e: { target: { checked: boolean } }) {
  toggleAll('layer', e.target.checked);
}
function onAllRoots(e: { target: { checked: boolean } }) {
  toggleAll('root', e.target.checked);
}
function onAllGrades(e: { target: { checked: boolean } }) {
  toggleAll('grade', e.target.checked);
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
}

async function send(text?: string) {
  const content = (text ?? draft.value).trim();
  if (!content || thinking.value || !canUseSpecAi.value) return;
  draft.value = '';
  turns.value.push({ role: 'user', text: content });
  thinking.value = true;
  await scroll();
  let llmText = '';
  let fallback = false;
  if (useRemoteApi() && currentProject.value?.id) {
    try {
      const r = await api.ai.chat({
        message: content,
        slot: isAsk.value ? 'spec.ask.system' : 'spec.system',
        projectId: currentProject.value.id,
      });
      if (r.fallback) {
        fallback = true;
        message.warning(r.error || '大模型代发失败，改用内置草案');
      }
      if (r.text) llmText = r.text;
    } catch (e) {
      fallback = true;
      message.warning(e instanceof Error ? e.message : '大模型代发失败，改用内置草案');
    }
  }
  const prompt = llmText && isDesign.value ? `${content}\n\n（模型建议：${llmText}）` : content;
  const reply = replySpecChat(prompt, isDesign.value ? proposal.value : null);
  if (isDesign.value) {
    const isFresh =
      !proposal.value ||
      reply.proposal.industry !== proposal.value.industry ||
      /帮我设计|重新设计|整个数仓|我做的是/.test(content);
    if (isFresh) selectAll(reply.proposal);
    else mergeSelection(reply.proposal);
    proposal.value = reply.proposal;
  }
  turns.value.push({
    role: 'assistant',
    text: !fallback && llmText ? llmText : reply.text,
  });
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
  if (!proposal.value) return;
  Modal.confirm({
    title: '同步进当前项目？',
    content: `将写入 ${pickedDomains.value.length} 个主题域、${pickedLayers.value.length} 条分层、${pickedGrades.value.length} 个等级、${pickedRoots.value.length} 个词根。${overwrite.value ? '已存在的编码会被覆盖。' : '已存在的编码会跳过。'}`,
    okText: '确认同步',
    onOk() {
      applySpecProposal({
        domains: pickedDomains.value,
        layers: pickedLayers.value,
        roots: pickedRoots.value,
        grades: pickedGrades.value,
        overwrite: overwrite.value,
      });
      router.push('/model/spec/domains');
    },
  });
}

watch(overwrite, persistChat);

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
.split.ask {
  grid-template-columns: 1fr;
}
.idle-ask {
  color: var(--muted);
  font-size: 13px;
  padding: 24px 8px;
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
  background: #fff;
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
  color: #64748b;
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
  background: #f8fafc;
  cursor: pointer;
  font-size: 13px;
}
.chip:hover {
  border-color: #0e7490;
}
.msg {
  margin-bottom: 12px;
}
.who {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 4px;
}
.bubble {
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f1f5f9;
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
  color: #64748b;
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
.block {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.block-h {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}
.pick {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 8px 0;
  border-top: 1px dashed #eef2f6;
  cursor: pointer;
}
.pick code,
.root code {
  font-size: 12px;
}
.roots {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.root {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  cursor: pointer;
}
.field {
  font-weight: 400;
  font-size: 12px;
}
</style>
