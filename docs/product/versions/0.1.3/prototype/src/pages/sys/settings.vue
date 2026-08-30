<template>
  <div class="page">
    <PageHeader
      :title="scope === 'platform' ? '外观与布局' : '设置'"
      :subtitle="
        scope === 'platform'
          ? '只改平台管理后台的主题和菜单位置，不会带到任何租户的工作台或项目。'
          : '本租户工作台与项目的外观、大模型，以及 AI 提示词。与平台管理后台互不影响。'
      "
    />

    <section class="card">
      <h3>主题</h3>
      <div class="themes">
        <button
          v-for="t in THEME_OPTIONS"
          :key="t.id"
          type="button"
          class="theme"
          :class="{ on: appearance.theme === t.id }"
          @click="setTheme(scope, t.id, tenantId)"
        >
          <i :style="{ background: t.primary }" />
          <div>
            <b>{{ t.label }}</b>
            <span>{{ t.desc }}</span>
          </div>
        </button>
      </div>
    </section>

    <section class="card mt">
      <h3>菜单栏位置</h3>
      <p class="muted">
        {{
          scope === 'platform'
            ? '仅平台管理：主菜单在左侧或顶部。'
            : '仅本租户（工作台 + 项目）：主菜单在左侧或顶部。'
        }}
        子菜单在左侧分组显示，在顶部从主菜单展开。
      </p>
      <a-radio-group :value="appearance.menuPos" @change="onPos">
        <a-radio-button value="left">左侧</a-radio-button>
        <a-radio-button value="top">顶部</a-radio-button>
      </a-radio-group>
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
      <h3>AI 提示词</h3>
      <p class="muted">
        按槽位覆盖产品默认，不从零写整套逻辑。代发前由服务端替换占位符。
        <template v-if="!llm.enabled">先启用大模型，提示词才会用于代发；未开启时仍走内置草案，不读这些槽位。</template>
      </p>
      <p class="vars">
        可用变量：
        <code v-for="p in AI_PROMPT_PLACEHOLDERS" :key="p.key">{{ p.key }}</code>
      </p>
      <div v-for="s in AI_PROMPT_SLOTS" :key="s.slot" class="slot">
        <div class="slot-h">
          <b>{{ s.label }}</b>
          <span>{{ s.hint }}</span>
          <a-button size="small" @click="resetSlot(s.slot)">恢复默认</a-button>
        </div>
        <a-textarea v-model:value="promptDraft[s.slot]" :auto-size="{ minRows: 4, maxRows: 10 }" />
      </div>
      <a-button type="primary" :disabled="!tenantId" @click="savePrompts">保存提示词</a-button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useRoute } from 'vue-router';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { app } from '../../stores/app';
import { AI_PROMPT_PLACEHOLDERS, AI_PROMPT_SLOTS, DEFAULT_AI_PROMPTS } from '../../config/aiPrompts';
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
  setTheme,
  THEME_OPTIONS,
  type AppearanceScope,
  type LlmConfig,
  type LlmProvider,
  type MenuPos,
} from '../../stores/prefs';

const route = useRoute();
const scope = computed<AppearanceScope>(() => (route.path.startsWith('/admin') ? 'platform' : 'tenant'));
const tenantId = computed(() => (scope.value === 'tenant' ? app.currentTenantId : null));
const appearance = computed(() => appearanceOf(scope.value, tenantId.value));
const providerOpts = LLM_PROVIDERS.map((p) => ({ value: p.value, label: p.label }));

const llm = reactive<LlmConfig>({ ...llmOf(tenantId.value) });
const promptDraft = reactive<Record<AiPromptSlot, string>>({
  'spec.system': DEFAULT_AI_PROMPTS['spec.system'],
  'spec.ask.system': DEFAULT_AI_PROMPTS['spec.ask.system'],
  'model.system': DEFAULT_AI_PROMPTS['model.system'],
});

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

.vars {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--muted);
}

.vars code {
  margin-right: 8px;
  font-size: 11px;
}

.slot {
  margin-bottom: 16px;
}

.slot-h {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}

.slot-h b {
  font-size: 13px;
}

.slot-h span {
  flex: 1;
  font-size: 12px;
  color: var(--muted);
}
</style>
