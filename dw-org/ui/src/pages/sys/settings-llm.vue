<template>
  <div class="page">
    <PageHeader
      title="大模型"
      subtitle="给本组织的规范助手、建模 AI 设计等对话使用。密钥加密存在服务端，接口不会回传明文。未开启时走内置草案。"
    />

    <section class="card">
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
          <a-input-password
            v-model:value="llm.apiKey"
            :placeholder="llm.hasKey ? '已保存，留空则不改' : 'sk-…'"
            autocomplete="off"
          />
        </a-form-item>
        <a-form-item label="模型">
          <a-input v-model:value="llm.model" placeholder="gpt-4o / qwen-plus" />
        </a-form-item>
        <a-button type="primary" :disabled="!tenantId" @click="saveLlm">保存大模型配置</a-button>
      </a-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { app } from '../../stores/app';
import {
  LLM_PROVIDERS,
  llmOf,
  loadTenantLlm,
  setLlm,
  type LlmConfig,
  type LlmProvider,
} from '../../stores/prefs';

/**
 * 「大模型」页 —— 拆出来的三个设置子页之一。
 *
 * <p>只在工作台壳下存在（平台后台没有大模型配置），所以不需要像外观页那样按 `scope` 分叉。
 */
const tenantId = computed(() => app.currentTenantId);
const providerOpts = LLM_PROVIDERS.map((p) => ({ value: p.value, label: p.label }));

const llm = reactive<LlmConfig>({ ...llmOf(tenantId.value) });

// 先落一份本地态（可能是出厂值或缓存），拉到服务端的再覆盖一次 —— 与拆分前同一口径。
watch(
  tenantId,
  (id) => {
    Object.assign(llm, llmOf(id));
  },
  { immediate: true }
);

onMounted(async () => {
  if (!tenantId.value) return;
  await loadTenantLlm(tenantId.value);
  Object.assign(llm, llmOf(tenantId.value));
});

function onProvider(value: unknown) {
  const id = value as LlmProvider;
  const preset = LLM_PROVIDERS.find((p) => p.value === id);
  if (!preset) return;
  llm.provider = id;
  if (preset.baseUrl) llm.baseUrl = preset.baseUrl;
  if (preset.model) llm.model = preset.model;
}

async function saveLlm() {
  if (!tenantId.value) return;
  if (llm.enabled && (!llm.baseUrl.trim() || !llm.model.trim())) {
    message.warning('启用时请填写接口地址和模型名');
    return;
  }
  await setLlm(tenantId.value, { ...llm });
  Object.assign(llm, llmOf(tenantId.value));
  message.success('大模型配置已保存');
}
</script>

<style scoped>
.llm {
  max-width: 560px;
}
</style>
