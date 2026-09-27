<template>
  <div class="page">
    <PageHeader
      title="模块管理"
      subtitle="平台给本组织开通的模块，在这里决定启用哪些、给谁看。未开通的模块不会出现 —— 那是平台后台的事。"
    />

    <section class="card block">
      <h3>本组织启用与可见范围</h3>
      <p class="lead">
        关掉一个模块，它在项目侧栏里就没了。可见范围决定「谁能看见」：看不见的人侧栏里不会出现这一项
        （数据地图是例外，它始终出现，只是置灰并说明）。
      </p>

      <a-table
        :data-source="rows"
        :columns="cols"
        :loading="loading"
        row-key="product"
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'label'">
            {{ moduleLabel(record.product) }}
          </template>
          <template v-else-if="column.key === 'desc'">
            <span class="muted">{{ moduleDesc(record.product) }}</span>
          </template>
          <template v-else-if="column.key === 'svc'">
            <span v-if="record.frontendUrl" class="mono">{{ record.frontendUrl }}</span>
            <span v-else class="muted">平台未注册</span>
          </template>
          <template v-else-if="column.key === 'on'">
            <a-switch
              :checked="record.enabled"
              :disabled="!isTenantAdmin"
              :loading="saving"
              @change="(v: unknown) => setEnabled(record, Boolean(v))"
            />
          </template>
          <template v-else-if="column.key === 'vis'">
            <a-select
              :value="record.visibleTo"
              :options="visOpts"
              :disabled="!isTenantAdmin || !record.enabled"
              style="width: 230px"
              @change="(v: unknown) => setVisibleTo(record, String(v))"
            />
          </template>
        </template>
      </a-table>

      <p v-if="!rows.length && !loading" class="muted">平台尚未给本组织开通任何模块。</p>
      <ul v-else class="hint">
        <li>改动即时保存。<b>只提交你动过的模块</b> —— 没动过的保持原样，它们本来就不限可见范围。</li>
        <li>
          「平台服务」列是产品的<b>页面地址</b>（来自平台服务注册），不是后端地址；登记过就显示地址，
          没有则显示「平台未注册」。这里<b>没有在线/离线状态</b> —— 平台不探活产品进程。
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { VISIBLE_TO_OPTS, moduleDesc, moduleLabel } from '../../config/iam';
import { api, type ModulePolicyRow } from '../../api/client';
import { app, isTenantAdmin } from '../../stores/app';

const rows = ref<ModulePolicyRow[]>([]);
const loading = ref(false);
const saving = ref(false);
/**
 * 本次会话里**被用户动过**的模块。
 *
 * <p>保存时只提交「已经配过的」与「这次动过的」两类：其余模块在库里根本没有策略行，
 * 而「没有行」在生效侧的含义是**不判可见范围**（见后端 NavNodeService 第三层注释）。
 * 全量提交会把它们一并写成「显式配置」，于是默认的 `role_holders` 开始真的生效 ——
 * 用户没碰过那几行，侧栏却对一部分人少了几条，且不会有任何报错。
 */
const dirty = ref<Set<string>>(new Set());

const tenantId = computed(() => app.currentTenantId ?? '');
const visOpts = VISIBLE_TO_OPTS.map((o) => ({ value: o.value, label: o.label }));

const cols = [
  { title: '模块', key: 'label', width: 200 },
  { title: '说明', key: 'desc' },
  { title: '平台服务', key: 'svc', width: 260 },
  { title: '本组织启用', key: 'on', width: 110 },
  { title: '谁能看见', key: 'vis', width: 250 },
];

async function load() {
  if (!tenantId.value) return;
  loading.value = true;
  try {
    rows.value = await api.org.modules(tenantId.value);
    dirty.value = new Set();
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function setEnabled(row: ModulePolicyRow, on: boolean) {
  row.enabled = on;
  // 关掉时可见范围就没有意义了；一并标脏，让这次的关闭能落库。
  dirty.value.add(row.product);
  void save();
}

function setVisibleTo(row: ModulePolicyRow, visibleTo: string) {
  row.visibleTo = visibleTo;
  dirty.value.add(row.product);
  void save();
}

async function save() {
  if (!tenantId.value) return;
  const body = rows.value
    .filter((r) => r.explicit || dirty.value.has(r.product))
    .map((r) => ({ product: r.product, enabled: r.enabled, visibleTo: r.visibleTo }));
  saving.value = true;
  try {
    // 服务端回的就是保存后的最新状态，直接用它 —— 再拉一次会多一次往返，
    // 而且两次之间别人改了配置的话，页面会显示成别人的值。
    rows.value = await api.org.putModules(tenantId.value, body);
    dirty.value = new Set();
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败');
    // 保存失败要回到服务端的真实状态：留在本地的乐观值上，页面会显示一个并未生效的设置。
    await load();
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<style scoped>
.block {
  margin-bottom: 20px;
}

h3 {
  margin: 0 0 8px;
  font-size: 15px;
}

.lead,
.muted {
  color: var(--muted);
  font-size: 13px;
}

.lead {
  margin: 0 0 12px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.hint {
  margin: 12px 0 0;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
