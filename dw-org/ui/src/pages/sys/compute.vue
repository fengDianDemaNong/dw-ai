<template>
  <div class="page">
    <PageHeader
      title="计算资源"
      subtitle="调度集群和数仓引擎是本组织自己的基础设施，在这里登记。平台服务注册只登记产品进程（数仓建模 / 数据地图等），不管这些。"
    />

    <section class="card block">
      <h3>调度 · DolphinScheduler</h3>
      <p class="lead">
        在 DS 安全中心生成 Token，填到本组织。平台不代管这套集群，也不探测它是否在线 ——
        想知道配得对不对，点「测试连接」，结论会显示在这里。
      </p>

      <a-form layout="vertical" class="form">
        <a-form-item label="启用">
          <a-switch v-model:checked="schedulerEnabled" :disabled="!isTenantAdmin" />
        </a-form-item>
        <a-form-item label="API 基址">
          <a-input
            v-model:value="baseUrl"
            placeholder="http://10.20.0.15:12345/dolphinscheduler"
            :disabled="!isTenantAdmin"
          />
        </a-form-item>
        <a-form-item label="Access Token">
          <a-input-password
            v-model:value="token"
            :placeholder="hasToken ? '已保存，留空即不修改' : '在 DS 安全中心创建后粘贴'"
            autocomplete="off"
            :disabled="!isTenantAdmin"
          />
          <p class="hint">
            {{ hasToken ? '本组织已存有 Token（加密保存，不会回显）。' : '还没有设置 Token。' }}
            留空保存不会清掉已存的 Token。
          </p>
        </a-form-item>
        <a-form-item>
          <a-space wrap>
            <a-button type="primary" :loading="saving" :disabled="!isTenantAdmin" @click="save">
              保存
            </a-button>
            <a-button
              :loading="testing"
              :disabled="!isTenantAdmin || !baseUrl"
              @click="test"
            >
              测试连接
            </a-button>
            <a-tag :color="schedTag.color">{{ schedTag.text }}</a-tag>
          </a-space>
          <p v-if="cfg?.schedulerNote" class="hint">
            {{ cfg.schedulerTestedAt }} · {{ cfg.schedulerNote }}
          </p>
          <p v-else class="hint">「测试连接」用的是<b>已保存</b>的地址与 Token —— 改了先保存再测。</p>
        </a-form-item>
      </a-form>
    </section>

    <section class="card block">
      <h3>数仓引擎</h3>
      <p class="lead">
        和调度一样，属于本组织自己的计算资源。本版只登记启停，连接信息后续版本再配 ——
        所以「状态」一列只反映你有没有打开，不代表它一定连得上。
      </p>
      <a-table
        :data-source="engines"
        :columns="engineCols"
        row-key="kind"
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            {{ ENGINE_NAMES[record.kind] ?? record.kind }}
          </template>
          <template v-else-if="column.key === 'note'">
            {{ ENGINE_NOTES[record.kind] ?? '' }}
          </template>
          <template v-else-if="column.key === 'on'">
            <a-switch
              :checked="record.enabled"
              :disabled="!isTenantAdmin"
              :loading="saving"
              @change="(v: unknown) => setEngine(record, Boolean(v))"
            />
          </template>
          <template v-else-if="column.key === 'st'">
            <a-tag :color="record.enabled ? 'blue' : 'default'">
              {{ record.enabled ? '已启用' : '未启用' }}
            </a-tag>
          </template>
        </template>
      </a-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { api, type ComputeConfig, type ComputeEngineRow } from '../../api/client';
import { app, isTenantAdmin } from '../../stores/app';

const tenantId = computed(() => app.currentTenantId ?? '');

const schedulerEnabled = ref(false);
const baseUrl = ref('');
const token = ref('');
const engines = ref<ComputeEngineRow[]>([]);
const cfg = ref<ComputeConfig | null>(null);
const hasToken = computed(() => cfg.value?.hasToken ?? false);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);

/**
 * 引擎显示名。接口只回 `{kind, enabled}`，名字必须在这里映射 ——
 * 漏了它就等于「引擎」那一列整列空白（表格行还能点开关，只是不知道点的是谁）。
 *
 * <p>认不出的 kind 直接显示 kind 本身（见模板里的 `?? record.kind`）：后端将来加了引擎，
 * 页面上至少还能看出是哪一行，而不是一片空白。
 */
const ENGINE_NAMES: Record<string, string> = {
  hive: 'Hive',
  spark: 'Spark',
  clickhouse: 'ClickHouse',
  doris: 'Doris',
};

/**
 * 「说明」列：每个引擎的定位。
 *
 * <p>原型里这一列取的是数据自带的 `note`，而它的 kind 列表里根本没有 `note` ——
 * 原型自己那一列就是空的。这里补上技术定位，既不是营销文案也不是连接信息
 * （本版只登记启停，连接信息后续版本再配）。
 */
const ENGINE_NOTES: Record<string, string> = {
  hive: '离线数仓，SQL on Hadoop',
  spark: '批处理与 SQL 计算',
  clickhouse: '列式存储，实时分析',
  doris: 'MPP 架构，实时分析',
};

const engineCols = [
  { title: '引擎', key: 'name', width: 160 },
  { title: '启用', key: 'on', width: 90 },
  { title: '状态', key: 'st', width: 110 },
  { title: '说明', key: 'note' },
];

/**
 * 状态 tag 四态，照原型。
 *
 * <p>`error` 与 `unconfigured` 必须分开：前者是「测过、没通」（要去看 note 里的原因），
 * 后者是「还没测过」。合成一档会让管理员对着一个从没测过的地址以为已经试过了。
 */
const schedTag = computed(() => {
  const c = cfg.value;
  if (!c?.schedulerEnabled) return { color: 'default', text: '未启用' };
  if (c.schedulerStatus === 'ok') return { color: 'green', text: '已测通' };
  if (c.schedulerStatus === 'error') return { color: 'red', text: '未通过' };
  return { color: 'orange', text: '待测通' };
});

function apply(c: ComputeConfig) {
  cfg.value = c;
  schedulerEnabled.value = c.schedulerEnabled;
  baseUrl.value = c.schedulerBaseUrl;
  engines.value = c.engines.map((e) => ({ ...e }));
  // 明文框永远清空：服务端从不回传 Token，留着上一次输入的值会让人误以为它已保存。
  token.value = '';
}

async function load() {
  if (!tenantId.value) return;
  loading.value = true;
  try {
    apply(await api.org.compute(tenantId.value));
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!tenantId.value) return;
  saving.value = true;
  try {
    apply(
      await api.org.putCompute(tenantId.value, {
        schedulerEnabled: schedulerEnabled.value,
        schedulerBaseUrl: baseUrl.value,
        // 空串 = 不改：服务端只在这格非空时才加密覆写（见 ComputePutReq 的注释）。
        schedulerToken: token.value,
        engines: engines.value,
      })
    );
    message.success('已保存');
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function setEngine(row: ComputeEngineRow, on: boolean) {
  row.enabled = on;
  void save();
}

async function test() {
  if (!tenantId.value) return;
  testing.value = true;
  try {
    // 失败也回 200：连不上是预期结果之一，结论在 schedulerStatus / schedulerNote 里。
    apply(await api.org.testCompute(tenantId.value));
    const c = cfg.value;
    if (c?.schedulerStatus === 'ok') message.success('连接正常');
    else message.warning(c?.schedulerNote || '连接未通过');
  } catch (e) {
    message.error(e instanceof Error ? e.message : '测试失败');
  } finally {
    testing.value = false;
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
.hint {
  color: var(--muted);
  font-size: 13px;
}

.lead {
  margin: 0 0 12px;
}

.hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.form {
  max-width: 560px;
}
</style>
