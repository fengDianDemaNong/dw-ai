<template>
  <div class="page">
    <PageHeader
      :title="scope === 'platform' ? '外观与布局' : '设置'"
      :subtitle="
        scope === 'platform'
          ? '只改平台管理后台的主题和菜单位置，不会带到任何租户的工作台或项目。'
          : '本租户工作台与项目的外观、大模型与 AI 提示词。模块在「模块管理」，调度和引擎在「计算资源」，产品进程在平台「服务注册」。'
      "
    />

    <AppearancePickers :scope="scope" :tenant-id="tenantId" />

    <section class="card mt">
      <h3>菜单风格</h3>
      <p class="muted">
        {{
          scope === 'platform'
            ? '仅平台管理：收起、左侧或顶部。'
            : '仅本租户项目：收起是点左上角弹出；进入后左侧只留当前主菜单快捷项。也可改回全部左侧或顶部。'
        }}
      </p>
      <a-radio-group :value="appearance.menuPos" @change="onPos">
        <a-radio-button value="drawer">收起</a-radio-button>
        <a-radio-button value="left">左侧</a-radio-button>
        <a-radio-button value="top">顶部</a-radio-button>
      </a-radio-group>
    </section>

    <section v-if="scope === 'tenant'" class="card mt">
      <h3>已开通模块</h3>
      <p class="muted">
        地址在平台「服务注册」，本组织不填 URL。启用与可见范围到工作台
        <router-link to="/sys/modules">模块管理</router-link>。
      </p>
      <div v-for="row in productRows" :key="row.code" class="prod">
        <div class="prod-h">
          <b>{{ row.label }}</b>
          <a-tag :color="row.tagColor">{{ row.tag }}</a-tag>
        </div>
        <p class="hint">{{ row.desc }}</p>
        <p v-if="row.svc" class="hint">平台服务 {{ row.svc.baseUrl }} · {{ row.svc.status === 'online' ? '在线' : '离线' }}</p>
        <p v-else-if="row.licensed" class="hint">平台已开通，尚未注册在线服务。</p>
        <p v-else class="hint">平台未给本组织开通此产品。</p>
      </div>
    </section>

    <section v-if="scope === 'tenant'" class="card mt">
      <h3>大模型</h3>
      <p class="muted">
        给本租户的规范助手、建模 AI 设计等对话使用。密钥只存在本浏览器演示数据里，不提交到平台。未开启时仍走内置草案。
      </p>
      <a-form layout="vertical" class="llm">
        <a-form-item label="启用">
          <a-switch v-model:checked="llm.enabled" checked-children="开" un-checked-children="关" />
        </a-form-item>
        <a-form-item label="服务商">
          <a-select v-model:value="llm.provider" :options="providerOpts" @change="onProvider" />
        </a-form-item>
        <a-form-item label="接口地址">
          <a-input v-model:value="llm.baseUrl" placeholder="https://api.example.com/v1" />
        </a-form-item>
        <a-form-item label="API Key">
          <a-input-password v-model:value="llm.apiKey" placeholder="sk-…" autocomplete="off" />
        </a-form-item>
        <a-form-item label="模型">
          <a-input v-model:value="llm.model" placeholder="gpt-4o / qwen-plus" />
        </a-form-item>
        <a-button type="primary" :disabled="!tenantId" @click="saveLlm">保存大模型配置</a-button>
      </a-form>
    </section>

    <section v-if="scope === 'tenant'" class="card mt">
      <h3>AI 会改什么、走哪些接口</h3>
      <p class="muted">
        对话本身只换文本，确认后才写当前<strong>项目</strong>的数据。提示词按租户共用，注入的是当前项目摘要。
        未开大模型时不读下面的槽位，页面仍可用内置草案。
      </p>
      <a-table :data-source="opRows" :columns="opCols" :pagination="false" size="small" row-key="key" class="op-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'chat'">
            <code>{{ record.chat }}</code>
            <div class="cell-n">{{ record.chatNote }}</div>
          </template>
          <template v-else-if="column.key === 'confirm'">
            <code>{{ record.confirm }}</code>
            <div class="cell-n">{{ record.payload }}</div>
          </template>
          <template v-else-if="column.key === 'writes'">
            {{ record.writes }}
          </template>
        </template>
      </a-table>
    </section>

    <section v-if="scope === 'tenant'" class="card mt">
      <h3>AI 提示词</h3>
      <p class="muted">
        三个槽位覆盖产品默认，不从零写整套逻辑。代发前由服务端替换占位符。
        <template v-if="!llm.enabled">先启用大模型，提示词才会用于代发；未开启时仍走内置草案，不读这些槽位。</template>
      </p>
      <p class="vars">
        可用变量：
        <span v-for="p in AI_PROMPT_PLACEHOLDERS" :key="p.key" class="var">
          <code>{{ p.key }}</code>
          <em>{{ p.desc }}</em>
        </span>
      </p>
      <div v-for="s in AI_PROMPT_SLOTS" :key="s.slot" class="slot">
        <div class="slot-h">
          <div>
            <b>{{ s.label }}</b>
            <code class="slot-id">{{ s.slot }}</code>
            <span>{{ s.hint }}</span>
          </div>
          <a-button size="small" @click="resetSlot(s.slot)">恢复默认</a-button>
        </div>
        <dl class="meta">
          <div>
            <dt>用在</dt>
            <dd>{{ s.page }} · {{ s.cap }}</dd>
          </div>
          <div>
            <dt>对话</dt>
            <dd>
              <code>{{ s.chat }}</code>
              {{ s.chatNote }}
            </dd>
          </div>
          <div>
            <dt>确认后</dt>
            <dd>
              <code>{{ s.confirm }}</code>
              {{ s.payload }}
            </dd>
          </div>
          <div>
            <dt>会改的数据</dt>
            <dd>{{ s.writes }}</dd>
          </div>
        </dl>
        <a-textarea v-model:value="promptDraft[s.slot]" :auto-size="{ minRows: 8, maxRows: 18 }" />
      </div>
      <a-button type="primary" :disabled="!tenantId" @click="savePrompts">保存提示词</a-button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useRoute } from 'vue-router';
import { message } from 'ant-design-vue';
import AppearancePickers from '../../components/AppearancePickers.vue';
import PageHeader from '../../components/PageHeader.vue';
import { PRODUCT_MANIFESTS } from '../../config/products';
import { app, licensedProducts, openedProducts, serviceOf } from '../../stores/app';
import { AI_OPS_WITHOUT_SLOT, AI_PROMPT_PLACEHOLDERS, AI_PROMPT_SLOTS, DEFAULT_AI_PROMPTS } from '../../config/aiPrompts';
import type { AiPromptSlot } from '../../types';
import {
  appearanceOf,
  effectivePrompt,
  LLM_PROVIDERS,
  llmOf,
  resetPromptSlot,
  setLlm,
  setMenuPos,
  setPromptOverride,
  type AppearanceScope,
  type LlmConfig,
  type LlmProvider,
  type MenuPos,
} from '../../stores/prefs';

const route = useRoute();
const scope = computed<AppearanceScope>(() => (route.path.startsWith('/platform') ? 'platform' : 'tenant'));
const tenantId = computed(() => (scope.value === 'tenant' ? app.currentTenantId : null));
const appearance = computed(() => appearanceOf(scope.value, tenantId.value));
const providerOpts = LLM_PROVIDERS.map((p) => ({ value: p.value, label: p.label }));

const llm = reactive<LlmConfig>({ ...llmOf(tenantId.value) });
const promptDraft = reactive<Record<AiPromptSlot, string>>({
  'spec.system': DEFAULT_AI_PROMPTS['spec.system'],
  'spec.ask.system': DEFAULT_AI_PROMPTS['spec.ask.system'],
  'model.system': DEFAULT_AI_PROMPTS['model.system'],
});

const opRows = [
  ...AI_PROMPT_SLOTS.map((s) => ({
    key: s.slot,
    name: `${s.label}（${s.slot}）`,
    chat: s.chat,
    chatNote: s.chatNote,
    confirm: s.confirm,
    payload: s.payload,
    writes: s.writes,
  })),
  ...AI_OPS_WITHOUT_SLOT.map((s) => ({
    key: s.label,
    name: s.label,
    chat: s.chat,
    chatNote: s.prompt,
    confirm: s.confirm,
    payload: s.payload,
    writes: s.writes,
  })),
];

const productRows = computed(() => {
  const licensed = tenantId.value ? licensedProducts(tenantId.value) : [];
  const opened = tenantId.value ? openedProducts(tenantId.value) : [];
  return PRODUCT_MANIFESTS.map((p) => {
    const svc = serviceOf(p.code);
    const licensedYes = licensed.includes(p.code);
    const openedYes = opened.includes(p.code);
    let tag = '未开通';
    let tagColor = 'default';
    if (!licensedYes) {
      tag = '未开通';
    } else if (!openedYes) {
      tag = '本组织已关';
    } else if (svc?.status === 'online') {
      tag = '已启用';
      tagColor = 'green';
    } else {
      tag = '待注册服务';
      tagColor = 'orange';
    }
    return { ...p, licensed: licensedYes, opened: openedYes, svc, tag, tagColor };
  });
});

const opCols = [
  { title: '功能', dataIndex: 'name', width: 220 },
  { title: '对话接口', key: 'chat' },
  { title: '确认后接口 / payload', key: 'confirm' },
  { title: '会改的数据', key: 'writes', width: 280 },
];

function loadPrompts(id: string | null) {
  for (const s of AI_PROMPT_SLOTS) {
    promptDraft[s.slot] = effectivePrompt(id, s.slot);
  }
}

watch(
  tenantId,
  (id) => {
    Object.assign(llm, llmOf(id));
    loadPrompts(id);
  },
  { immediate: true }
);

function onPos(e: { target: { value: string | number | boolean } }) {
  setMenuPos(scope.value, e.target.value as MenuPos, tenantId.value);
}

function onProvider(value: unknown) {
  const id = value as LlmProvider;
  const preset = LLM_PROVIDERS.find((p) => p.value === id);
  if (!preset) return;
  llm.provider = id;
  if (preset.baseUrl) llm.baseUrl = preset.baseUrl;
  if (preset.model) llm.model = preset.model;
}

function saveLlm() {
  if (!tenantId.value) return;
  if (llm.enabled && (!llm.baseUrl.trim() || !llm.model.trim())) {
    message.warning('启用时请填写接口地址和模型名');
    return;
  }
  setLlm(tenantId.value, { ...llm });
  message.success('本租户大模型配置已保存');
}

function resetSlot(slot: AiPromptSlot) {
  if (!tenantId.value) return;
  resetPromptSlot(tenantId.value, slot);
  promptDraft[slot] = DEFAULT_AI_PROMPTS[slot];
  message.success('已恢复默认');
}

function savePrompts() {
  if (!tenantId.value) return;
  for (const s of AI_PROMPT_SLOTS) {
    setPromptOverride(tenantId.value, s.slot, promptDraft[s.slot]);
  }
  message.success('本租户提示词已保存');
}
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.themes {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.theme {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.theme.on {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 20%, transparent);
}

.theme i {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  flex-shrink: 0;
}

.theme b {
  display: block;
  font-size: 13px;
}

.theme span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}

.mt {
  margin-top: 16px;
}

.muted {
  margin: 0 0 12px;
}

.llm {
  max-width: 560px;
}

.op-table {
  margin-top: 4px;
}

.op-table :deep(code),
.meta code,
.slot-id {
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.06);
}

.prod {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.prod:last-child {
  border-bottom: 0;
}
.prod-h {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hint {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.cell-n {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
}

.vars {
  margin: 0 0 16px;
  font-size: 12px;
  color: var(--muted);
}

.var {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  margin: 0 12px 6px 0;
}

.var em {
  font-style: normal;
  color: var(--muted);
}

.slot {
  margin-bottom: 22px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line);
}

.slot:last-of-type {
  border-bottom: 0;
}

.slot-h {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.slot-h b {
  margin-right: 8px;
  font-size: 14px;
}

.slot-id {
  margin-right: 8px;
}

.slot-h span {
  display: inline;
  font-size: 12px;
  color: var(--muted);
}

.meta {
  display: grid;
  gap: 6px;
  margin: 0 0 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.02);
}

.meta > div {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
  font-size: 12px;
  line-height: 1.5;
}

.meta dt {
  margin: 0;
  color: var(--muted);
}

.meta dd {
  margin: 0;
}

@media (max-width: 900px) {
  .themes {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
