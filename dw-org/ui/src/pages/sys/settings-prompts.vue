<template>
  <div class="page">
    <PageHeader
      title="AI 提示词"
      subtitle="三个槽位覆盖产品默认，不从零写整套逻辑。提示词按组织共用，注入的是当前项目摘要；代发前由服务端替换占位符。"
    />

    <!--
      这张只读表留在提示词页，而不是单独一页：它讲的就是「下面这些槽位对应哪个接口、
      确认后会写什么数据」——与提示词分开摆，两边都得来回翻才看得懂。
    -->
    <section class="card">
      <h3>AI 会改什么、走哪些接口</h3>
      <p class="muted">
        对话本身只换文本，确认后才写当前<strong>项目</strong>的数据。未开大模型时不读下面的槽位，页面仍可用内置草案。
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

    <section class="card mt">
      <h3>提示词槽位</h3>
      <p class="muted">
        每个槽位独立保存；改成与默认一致的内容等于删掉这条覆盖。
        <template v-if="!llmEnabled">先启用大模型，提示词才会用于代发；未开启时仍走内置草案，不读这些槽位。</template>
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
            <a-tag v-if="overrides[s.slot]" color="blue" class="slot-tag">已自定义</a-tag>
          </div>
          <div class="slot-btns">
            <template v-if="expanded[s.slot]">
              <a-button size="small" @click="resetSlot(s.slot)">恢复默认</a-button>
              <a-button size="small" @click="collapse(s.slot)">收起</a-button>
              <a-button
                size="small"
                type="primary"
                :loading="slotBusy[s.slot]"
                @click="saveSlot(s.slot)"
              >
                保存
              </a-button>
            </template>
            <a-button v-else size="small" @click="expand(s.slot)">编辑</a-button>
          </div>
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
        <!--
          收起态给一行首行预览：这一页默认收起之后，「哪个槽位被改过、大概写成什么样」
          要还能一眼看到 —— 否则三个槽位收起来就成了一排一模一样的标题。
        -->
        <p v-if="!expanded[s.slot]" class="slot-preview">{{ firstLine(promptDraft[s.slot]) }}</p>
        <template v-else>
          <a-textarea v-model:value="promptDraft[s.slot]" :auto-size="{ minRows: 8, maxRows: 18 }" />
          <p class="muted slot-note">
            「恢复默认」只是把内容填回默认值，仍要点这一槽位的<b>保存</b>才生效
            —— 保存等于把这个槽位设成当前内容，等于默认时就删掉这条覆盖。
          </p>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { api, type AiPromptsDto } from '../../api/client';
import { AI_OPS_WITHOUT_SLOT, AI_PROMPT_PLACEHOLDERS, AI_PROMPT_SLOTS, DEFAULT_AI_PROMPTS } from '../../config/aiPrompts';
import { app } from '../../stores/app';
import type { AiPromptSlot } from '../../types';
import { llmOf, loadTenantLlm } from '../../stores/prefs';

/**
 * 「AI 提示词」页 —— 拆出来的三个设置子页之一，含「AI 会改什么」那张只读表。
 *
 * <p>只在工作台壳下存在，所以不按 `scope` 分叉（拆分前那三段的 `v-if="scope === 'workbench'"`
 * 就是这件事，现在由路由位置天然保证）。
 */
const tenantId = computed(() => app.currentTenantId);

/**
 * 只用来决定要不要显示「先启用大模型」那句提醒。
 *
 * <p>**自己拉一次**，不与「大模型」页共享：那页改完不等保存就切过来，读共享态会显示旧值；
 * 各页各拉一次，看到的永远是服务端当前的样子。
 */
const llmEnabled = ref(false);

const promptDraft = reactive<Record<AiPromptSlot, string>>({
  'spec.system': DEFAULT_AI_PROMPTS['spec.system'],
  'spec.ask.system': DEFAULT_AI_PROMPTS['spec.ask.system'],
  'model.system': DEFAULT_AI_PROMPTS['model.system'],
});

/**
 * 每个槽位**各自**的展开态与忙碌态 —— 这一页默认全收起，点「编辑」才展开。
 *
 * <p>收起态下三个槽位长得一模一样（同一套标题 + 同一张说明表），所以收起时必须给点
 * 差异化的东西，否则「哪个改过」要靠逐个展开去翻：右上角用 {@link overrides} 标
 * 「已自定义」，正文用首行预览。
 */
const expanded = reactive<Record<AiPromptSlot, boolean>>({
  'spec.system': false,
  'spec.ask.system': false,
  'model.system': false,
});
const slotBusy = reactive<Record<AiPromptSlot, boolean>>({
  'spec.system': false,
  'spec.ask.system': false,
  'model.system': false,
});

/**
 * **服务端当前存着的值**（不是草稿）。两个用处：判断有没有未保存的修改（收起时丢弃），
 * 以及给首行预览一个「改过之前长什么样」的底。
 */
const savedDraft = reactive<Record<AiPromptSlot, string>>({ ...promptDraft });

/** 真正存了覆盖的槽位（服务端 DTO 里的 `overrides`，不含默认值）。 */
const overrides = ref<Record<string, string>>({});

/** 首行预览：提示词第一行通常就是它的人话说明（见 `config/aiPrompts.ts` 的默认草案）。 */
function firstLine(body: string): string {
  const line = (body ?? '').split('\n').find((l) => l.trim().length > 0) ?? '';
  return line.trim();
}

function labelOf(slot: AiPromptSlot): string {
  return AI_PROMPT_SLOTS.find((s) => s.slot === slot)?.label ?? slot;
}

function expand(slot: AiPromptSlot) {
  expanded[slot] = true;
}

/**
 * 收起 = **放弃未保存的修改**。
 *
 * <p>不给「收起即保留草稿」：那样一个槽位会同时存在「屏幕上的内容」与「服务端的值」两份，
 * 而这一页的语义是「保存才生效」（下方说明文字也是这么写的）—— 保留草稿会让「我明明改了、
 * 怎么没生效」变成默不作声的常见状态。
 */
function collapse(slot: AiPromptSlot) {
  if (promptDraft[slot] !== savedDraft[slot]) {
    promptDraft[slot] = savedDraft[slot];
    message.info('未保存的修改已丢弃');
  }
  expanded[slot] = false;
}

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

const opCols = [
  { title: '功能', dataIndex: 'name', width: 220 },
  { title: '对话接口', key: 'chat' },
  { title: '确认后接口 / payload', key: 'confirm' },
  { title: '会改的数据', key: 'writes', width: 280 },
];

/** 把服务端 DTO 落到草稿 + 已存值 + 「已自定义」标记上；`null` = 回到内置默认。 */
function applyDto(dto: AiPromptsDto | null) {
  for (const s of AI_PROMPT_SLOTS) {
    const v = dto?.effective?.[s.slot] ?? DEFAULT_AI_PROMPTS[s.slot];
    promptDraft[s.slot] = v;
    savedDraft[s.slot] = v;
  }
  overrides.value = dto?.overrides ?? {};
}

async function loadPrompts(id: string | null) {
  applyDto(null);
  if (!id) return;
  try {
    applyDto(await api.org.aiPrompts(id));
  } catch {
    /* 用默认 */
  }
}

watch(
  tenantId,
  (id) => {
    llmEnabled.value = llmOf(id).enabled;
    void loadPrompts(id);
  },
  { immediate: true }
);

onMounted(async () => {
  if (!tenantId.value) return;
  await loadTenantLlm(tenantId.value);
  llmEnabled.value = llmOf(tenantId.value).enabled;
  await loadPrompts(tenantId.value);
});

function resetSlot(slot: AiPromptSlot) {
  promptDraft[slot] = DEFAULT_AI_PROMPTS[slot];
  message.success('已填回默认内容（点「保存」后生效）');
}

/**
 * 存**这一个**槽位。
 *
 * <p>后端 `PUT /ai-prompts` 是按传进来的 key **逐个** upsert/delete 的（不是整体替换），
 * 所以只发一个槽位不会动到另外两个 —— 这正是「每个提示词各自保存」能成立的前提。
 *
 * <p>**这个槽位总是要发出去，哪怕它等于默认**：后端「内容为空或等于默认 → 删掉这条覆盖」
 * 那条分支，只有在收到这个 key 时才会走到。按「等于默认就滤掉不发」的写法，
 * 「恢复默认 + 保存」会静默不生效 —— 页面上显示回了默认，库里那条覆盖其实还在。
 */
async function saveSlot(slot: AiPromptSlot) {
  if (!tenantId.value) return;
  slotBusy[slot] = true;
  try {
    applyDto(await api.org.putAiPrompts(tenantId.value, { [slot]: promptDraft[slot].trim() }));
    message.success(`「${labelOf(slot)}」已保存`);
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    slotBusy[slot] = false;
  }
}
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.mt {
  margin-top: 16px;
}

.muted {
  margin: 0 0 12px;
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

/* 展开后右侧是「恢复默认 / 收起 / 保存」三个按钮，不许被标题挤到换行 */
.slot-btns {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.slot-tag {
  margin-left: 8px;
  font-size: 11px;
  line-height: 18px;
}

/* 收起态的首行预览：单行截断，不占高度 */
.slot-preview {
  margin: 0;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.slot-note {
  margin: 8px 0 0;
  font-size: 12px;
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
</style>
