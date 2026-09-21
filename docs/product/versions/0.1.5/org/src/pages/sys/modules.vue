<template>
  <div class="page">
    <PageHeader
      title="模块管理"
      subtitle="平台给本组织开通的是上限。这里再决定开哪些、谁能看见。地址在平台服务注册，租户不填 URL。"
    />

    <p class="lead">
      关掉的模块从项目侧栏消失。可见范围按权限裁：同一租户里，张三、李四、王五看到的模块可以不同。
    </p>

    <a-table :data-source="rows" :columns="cols" row-key="code" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'svc'">
          <template v-if="record.svc">
            <div>{{ record.svc.baseUrl }}</div>
            <a-tag :color="record.svc.status === 'online' ? 'green' : 'default'">
              {{ record.svc.status === 'online' ? '在线' : '离线' }}
            </a-tag>
          </template>
          <span v-else class="muted">平台未注册</span>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-switch
            :checked="record.enabled"
            checked-children="开"
            un-checked-children="关"
            @change="(v: unknown) => setModulePolicy(tenantId, record.code, { enabled: Boolean(v) })"
          />
        </template>
        <template v-else-if="column.key === 'visible'">
          <a-select
            :value="record.visibleTo"
            :options="[...VISIBLE_TO_OPTS]"
            style="width: 260px"
            :disabled="!record.enabled"
            @change="(v: unknown) => setModulePolicy(tenantId, record.code, { visibleTo: v as typeof record.visibleTo })"
          />
        </template>
      </template>
    </a-table>

    <p v-if="!rows.length" class="muted empty">平台尚未给本组织开通任何产品。</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import { PRODUCT_MANIFESTS, VISIBLE_TO_OPTS } from '../../config/products';
import { app, licensedProducts, modulePolicyOf, serviceOf, setModulePolicy } from '../../stores/app';

const tenantId = computed(() => app.currentTenantId ?? '');

const rows = computed(() => {
  const tid = tenantId.value;
  return PRODUCT_MANIFESTS.filter((p) => licensedProducts(tid).includes(p.code)).map((p) => {
    const policy = modulePolicyOf(tid, p.code);
    return {
      code: p.code,
      label: p.label,
      desc: p.desc,
      enabled: policy.enabled,
      visibleTo: policy.visibleTo,
      svc: serviceOf(p.code),
    };
  });
});

const cols = [
  { title: '模块', dataIndex: 'label', width: 120 },
  { title: '说明', dataIndex: 'desc' },
  { title: '平台服务', key: 'svc', width: 220 },
  { title: '本组织启用', key: 'enabled', width: 110 },
  { title: '谁能看见', key: 'visible', width: 280 },
];
</script>

<style scoped>
.lead,
.muted {
  color: var(--muted);
  font-size: 13px;
}
.lead {
  margin: 0 0 12px;
}
.empty {
  margin-top: 16px;
}
</style>
