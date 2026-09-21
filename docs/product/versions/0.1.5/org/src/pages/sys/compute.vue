<template>
  <div class="page">
    <PageHeader
      title="计算资源"
      subtitle="调度集群和数仓引擎是本组织自己的基础设施，在这里登记。平台服务注册只登记产品进程（建模 / 元数据等），不管这些。"
    />

    <section class="card">
      <h3>调度 · DolphinScheduler</h3>
      <p class="muted">
        在 DS 安全中心生成 Token，填到本租户。启动、补数、重跑都走这条地址。平台不保存、不代管这套集群。
      </p>
      <a-form layout="vertical" class="form">
        <a-form-item label="启用">
          <a-switch
            :checked="scheduler.enabled"
            checked-children="开"
            un-checked-children="关"
            @change="(v: unknown) => saveSched({ enabled: Boolean(v) })"
          />
        </a-form-item>
        <a-form-item label="API 基址">
          <a-input
            :value="scheduler.baseUrl"
            placeholder="http://10.20.0.15:12345/dolphinscheduler"
            @change="(e: Event) => saveSched({ baseUrl: (e.target as HTMLInputElement).value })"
          />
        </a-form-item>
        <a-form-item label="Access Token">
          <a-input-password
            :value="scheduler.token"
            placeholder="在 DS 安全中心创建后粘贴"
            autocomplete="off"
            @change="(e: Event) => saveSched({ token: (e.target as HTMLInputElement).value })"
          />
          <p v-if="scheduler.tokenMasked" class="hint">当前 {{ scheduler.tokenMasked }}</p>
        </a-form-item>
        <p v-if="scheduler.note" class="hint">{{ scheduler.lastTestAt }} · {{ scheduler.note }}</p>
        <a-space>
          <a-tag :color="schedTag.color">{{ schedTag.text }}</a-tag>
          <a-button type="primary" :disabled="!tenantId" @click="test">测试连接</a-button>
        </a-space>
      </a-form>
    </section>

    <section class="card mt">
      <h3>数仓引擎</h3>
      <p class="muted">和调度一样，属于本组织自己的计算资源。连接信息后期在此配置，不进平台注册表。</p>
      <a-table :data-source="engines" :columns="engineCols" row-key="kind" :pagination="false" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'on'">
            <a-switch
              :checked="record.enabled"
              checked-children="开"
              un-checked-children="关"
              @change="(v: unknown) => saveEngine({ ...record, enabled: Boolean(v) })"
            />
          </template>
          <template v-else-if="column.key === 'st'">
            <a-tag>{{ record.status === 'ok' ? '已接' : '待配连接' }}</a-tag>
          </template>
        </template>
      </a-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import type { TenantEngineBind, TenantScheduler } from '../../types';
import {
  app,
  setTenantEngineBind,
  setTenantScheduler,
  tenantEngineBinds,
  tenantScheduler,
  testTenantScheduler,
} from '../../stores/app';

const tenantId = computed(() => app.currentTenantId ?? '');

const scheduler = computed<TenantScheduler>(
  () =>
    tenantScheduler(tenantId.value) ?? {
      provider: 'dolphinscheduler',
      enabled: false,
      baseUrl: '',
      status: 'unconfigured',
    }
);

const schedTag = computed(() => {
  const s = scheduler.value;
  if (!s.enabled) return { color: 'default', text: '未启用' };
  if (s.status === 'ok') return { color: 'green', text: '已测通' };
  if (s.status === 'error') return { color: 'red', text: '未通过' };
  return { color: 'orange', text: '待测通' };
});

const engines = computed(() => {
  const have = tenantEngineBinds(tenantId.value);
  const kinds = [
    { kind: 'hive' as const, name: 'Hive' },
    { kind: 'spark' as const, name: 'Spark' },
    { kind: 'clickhouse' as const, name: 'ClickHouse' },
    { kind: 'doris' as const, name: 'Doris' },
  ];
  return kinds.map((k) => have.find((e) => e.kind === k.kind) ?? { ...k, enabled: false, status: 'unconfigured' as const });
});

const engineCols = [
  { title: '引擎', dataIndex: 'name', width: 140 },
  { title: '启用', key: 'on', width: 90 },
  { title: '状态', key: 'st', width: 110 },
  { title: '说明', dataIndex: 'note' },
];

function saveSched(patch: Partial<TenantScheduler>) {
  if (!tenantId.value) return;
  setTenantScheduler(tenantId.value, patch);
}

function test() {
  if (!tenantId.value) return;
  testTenantScheduler(tenantId.value);
}

function saveEngine(row: TenantEngineBind) {
  if (!tenantId.value) return;
  setTenantEngineBind(tenantId.value, row);
}
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}
.muted,
.hint {
  color: var(--muted);
  font-size: 13px;
}
.muted {
  margin: 0 0 12px;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
}
.form {
  max-width: 560px;
}
.mt {
  margin-top: 16px;
}
</style>
