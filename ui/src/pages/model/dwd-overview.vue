<template>
  <div class="page">
    <PageHeader :title="layer" :subtitle="rule?.note || '按分层规范建模。先看总览，再进域看表。'">
      <template #actions>
        <a-button @click="router.push(specLayerHref(layer))">规则设定</a-button>
        <a-tooltip :title="hasAiCap('model_design') ? '' : '本组织未开通此项'">
          <a-button :disabled="!hasAiCap('model_design')" @click="router.push(layerAiHref(layer))">AI 设计</a-button>
        </a-tooltip>
        <a-button v-if="generateTo" @click="router.push(generateTo)">从 {{ prev?.layer }} 生成</a-button>
        <a-button v-if="canWrite" @click="openCreateDomain">新增主题域</a-button>
        <a-button v-if="canWrite" type="primary" @click="creating = true">新增表</a-button>
      </template>
    </PageHeader>

    <p v-if="!rule" class="muted warn">该分层未在「分层规范」中登记，侧栏不会显示。可到规范中心补登记。</p>

    <div v-if="policy" class="policy">
      <div>
        <span>字段格式</span>
        <p>{{ policy.fieldFormat }}</p>
      </div>
      <div>
        <span>脱敏</span>
        <p>{{ maskingLabel(policy.masking) }} · {{ policy.maskingNote }}</p>
      </div>
      <div>
        <span>空值</span>
        <p>
          {{ nullLabel(policy.nullHandling) }}
          <template v-if="policy.nullHandling === 'fill' && policy.nullFill">（{{ policy.nullFill }}）</template>
        </p>
      </div>
    </div>

    <section v-if="prev" class="block" :class="layerTone(prev.layer)">
      <header>
        <span class="pill">{{ prev.layer }}</span>
        <div>
          <h2>{{ prev.layer }}</h2>
          <p>{{ prev.note || '上一层，作为本层加工输入' }}</p>
        </div>
        <span class="count">{{ prevTotal }} 张表</span>
      </header>
      <div v-if="prevCards.length" class="cards">
        <button
          v-for="c in prevCards"
          :key="c.code"
          type="button"
          class="mini"
          @click="router.push(layerHref(prev.layer))"
        >
          <div class="mini-t">{{ c.name }}</div>
          <code v-for="t in c.tables.slice(0, 2)" :key="t.id">{{ t.name }}</code>
          <div class="mini-k">{{ c.hint }}</div>
        </button>
      </div>
      <div v-else class="empty">还没有 {{ prev.layer }} 表。</div>
    </section>

    <div v-if="prev" class="flow">↓ {{ rule?.note || '加工入本层' }}</div>

    <section class="block current" :class="layerTone(layer)">
      <header>
        <span class="pill">{{ layer }}</span>
        <div>
          <h2>{{ layer }}</h2>
          <p>{{ rule?.note || '主题域建模' }}</p>
        </div>
        <span class="count">{{ total }} 张表 · {{ cards.length }} 个主题域</span>
      </header>
      <div class="cards domain-cards">
        <article v-for="c in cards" :key="c.code" class="domain" @click="goList(c.code)">
          <div class="d-top">
            <div class="d-title">
              <span class="icon">{{ c.icon }}</span>
              <b>{{ c.name }}</b>
              <span class="code">{{ c.code === '_none' ? '' : c.code }}</span>
            </div>
            <span class="badge">{{ c.tables.length }} 张表</span>
          </div>
          <code
            v-for="t in c.tables.slice(0, 3)"
            :key="t.id"
            @click.stop="goDetail(c.code, t.id)"
          >{{ t.name }}</code>
          <span v-if="!c.tables.length" class="empty-tbl">暂无表，点击进入后可新增</span>
          <p>{{ c.definition }}</p>
          <div v-if="c.domain && canWrite" class="ops" @click.stop>
            <a @click="openEditDomain(c.domain)">编辑</a>
            <a-popconfirm
              :title="c.tables.length ? '该主题域仍被表引用，无法删除。' : '确定删除该主题域？'"
              @confirm="removeDomain(c.domain.id)"
            >
              <a class="danger">删除</a>
            </a-popconfirm>
          </div>
        </article>
      </div>
    </section>

    <TableFormModal
      :open="creating"
      :layer="layer"
      :default-domain="cards[0]?.code === '_none' ? undefined : cards[0]?.code"
      @close="creating = false"
    />

    <a-modal v-model:open="domainOpen" :title="editingDomainId ? '编辑主题域' : '新增主题域'" ok-text="保存" @ok="saveDomain">
      <a-form layout="vertical">
        <a-form-item label="编码" required>
          <a-input v-model:value="domainForm.code" placeholder="TRD" :disabled="Boolean(editingDomainId)" />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="domainForm.name" placeholder="交易域" />
        </a-form-item>
        <a-form-item label="业务定义">
          <a-textarea v-model:value="domainForm.definition" :rows="2" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import TableFormModal from '../../components/TableFormModal.vue';
import { generateHref, layerAiHref, layerHref, layerTone, parseLayerParam } from '../../config/layers';
import { hydrateLayerRule, maskingLabel, nullLabel, specLayerHref } from '../../config/layerPolicies';
import { addDomain, can, hasAiCap, projectDomains, projectLayerRules, projectTables, removeDomain, updateDomain } from '../../stores/app';
import type { Domain, WarehouseTable } from '../../types';

const route = useRoute();
const router = useRouter();
const canWrite = computed(() => can('model:write'));
const creating = ref(false);
const domainOpen = ref(false);
const editingDomainId = ref<string | null>(null);
const domainForm = reactive({ code: '', name: '', definition: '' });
const NONE = '_none';
const ICONS = ['🎯', '📊', '👍', '🎨', '📦', '⚡'];

const layer = computed(() => parseLayerParam(route.params.layer));
const rules = projectLayerRules;
const rule = computed(() => rules.value.find((r) => r.layer === layer.value));
const policy = computed(() => (rule.value ? hydrateLayerRule(rule.value) : undefined));
const layerIndex = computed(() => rules.value.findIndex((r) => r.layer === layer.value));
const prev = computed(() => (layerIndex.value > 0 ? rules.value[layerIndex.value - 1] : undefined));
const generateTo = computed(() => (prev.value ? generateHref(prev.value.layer, layer.value) : null));

function byDomain(code: string) {
  const tables = projectTables.value.filter((t) => t.layer === code);
  const map = new Map<string, WarehouseTable[]>();
  for (const t of tables) {
    const key = t.domain || NONE;
    const list = map.get(key) ?? [];
    list.push(t);
    map.set(key, list);
  }
  return map;
}

const cards = computed(() => {
  const grouped = byDomain(layer.value);
  const domains = projectDomains.value;
  const seen = new Set<string>();
  const out: {
    code: string;
    name: string;
    definition: string;
    tables: WarehouseTable[];
    domain?: Domain;
    icon: string;
  }[] = [];
  domains.forEach((d, i) => {
    seen.add(d.code);
    out.push({
      code: d.code,
      name: d.name,
      definition: d.definition,
      tables: grouped.get(d.code) ?? [],
      domain: d,
      icon: ICONS[i % ICONS.length],
    });
  });
  for (const [code, tables] of grouped) {
    if (seen.has(code)) continue;
    out.push({
      code,
      name: code === NONE ? '未归属' : code,
      definition: '尚未登记到规范中心的主题域，表已存在。',
      tables,
      icon: '📁',
    });
  }
  if (!out.length) {
    out.push({
      code: NONE,
      name: '未归属',
      definition: `还没有 ${layer.value} 表，可以新增。`,
      tables: [],
      icon: '📁',
    });
  }
  return out;
});

const prevCards = computed(() => {
  if (!prev.value) return [];
  return [...byDomain(prev.value.layer).entries()].map(([code, tables]) => {
    const d = projectDomains.value.find((x) => x.code === code);
    return {
      code,
      name: d?.name ?? (code === NONE ? '未归属' : code),
      tables,
      hint: tables[0]?.comment || d?.coreEntities.slice(0, 4).join(' / ') || '',
    };
  });
});

const total = computed(() => projectTables.value.filter((t) => t.layer === layer.value).length);
const prevTotal = computed(() =>
  prev.value ? projectTables.value.filter((t) => t.layer === prev.value!.layer).length : 0
);

function goList(code: string) {
  router.push(`${layerHref(layer.value)}/${code}`);
}
function goDetail(code: string, id: string) {
  router.push(`${layerHref(layer.value)}/${code}/${id}`);
}

function openCreateDomain() {
  editingDomainId.value = null;
  domainForm.code = '';
  domainForm.name = '';
  domainForm.definition = '';
  domainOpen.value = true;
}

function openEditDomain(d: Domain) {
  editingDomainId.value = d.id;
  domainForm.code = d.code;
  domainForm.name = d.name;
  domainForm.definition = d.definition;
  domainOpen.value = true;
}

function saveDomain() {
  if (!domainForm.code || !domainForm.name) {
    message.warning('编码和名称必填');
    return;
  }
  const payload = {
    code: domainForm.code,
    name: domainForm.name,
    definition: domainForm.definition,
    bizOwner: '张三',
    techOwner: '李四',
    dataOwner: '王五',
    related: [] as string[],
    coreEntities: [] as string[],
  };
  const exist = editingDomainId.value
    ? projectDomains.value.find((d) => d.id === editingDomainId.value)
    : undefined;
  if (exist) {
    const ok = updateDomain(exist.id, {
      ...payload,
      bizOwner: exist.bizOwner,
      techOwner: exist.techOwner,
      dataOwner: exist.dataOwner,
      related: exist.related,
      coreEntities: exist.coreEntities,
    });
    if (ok) domainOpen.value = false;
    return;
  }
  if (addDomain(payload)) domainOpen.value = false;
}
</script>

<style scoped>
h2 {
  margin: 0;
  font-size: 16px;
}
header p,
.mini-k,
.domain p,
.empty,
.empty-tbl {
  margin: 2px 0 0;
  color: #64748b;
  font-size: 12px;
}
.empty-tbl {
  display: block;
  margin: 6px 0;
}
.warn {
  margin-bottom: 12px;
}
.policy {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.policy > div {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 10px 12px;
}
.policy span {
  font-size: 11px;
  color: #64748b;
}
.policy p {
  margin: 4px 0 0;
  font-size: 13px;
  color: #0f172a;
  line-height: 1.45;
}
@media (max-width: 900px) {
  .policy {
    grid-template-columns: 1fr;
  }
}
.block {
  border-radius: 14px;
  padding: 16px 18px 18px;
  margin-bottom: 8px;
}
.block header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.block header > div {
  flex: 1;
}
.pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
}
.ods {
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
}
.ods .pill {
  background: #475569;
}
.dwd {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}
.dwd .pill {
  background: #059669;
}
.dws {
  background: #eef6ff;
  border: 1px solid #bfdbfe;
}
.dws .pill {
  background: #2563eb;
}
.ads {
  background: #f5f3ff;
  border: 1px solid #ddd6fe;
}
.ads .pill {
  background: #7c3aed;
}
.dim {
  background: #fff7ed;
  border: 1px solid #fed7aa;
}
.dim .pill {
  background: #ea580c;
}
.stg {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}
.stg .pill {
  background: #64748b;
}
.count {
  font-size: 13px;
  color: #475569;
}
.flow {
  text-align: center;
  color: #64748b;
  font-size: 13px;
  margin: 10px 0 12px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.domain-cards {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.mini,
.domain {
  text-align: left;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px 14px;
  cursor: pointer;
}
.mini:hover,
.domain:hover {
  border-color: #0e7490;
  box-shadow: 0 6px 18px rgba(14, 116, 144, 0.08);
}
.mini-t {
  font-weight: 650;
  margin-bottom: 6px;
}
code {
  display: block;
  font-size: 12px;
  color: #0f766e;
  margin: 2px 0;
}
.d-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  gap: 8px;
}
.d-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.icon {
  font-size: 16px;
}
.code {
  color: #94a3b8;
  font-size: 12px;
  font-weight: 500;
}
.badge {
  background: #e2e8f0;
  color: #334155;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.dwd .badge {
  background: #d1fae5;
  color: #047857;
}
.ops {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  font-size: 13px;
}
@media (max-width: 900px) {
  .cards,
  .domain-cards {
    grid-template-columns: 1fr;
  }
}
</style>
