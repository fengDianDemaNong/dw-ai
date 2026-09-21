<template>
  <div class="scope-rows">
    <div class="row">
      <div class="row-label">
        租户
        <div class="row-hint">两个租户之间的血缘、元数据、元数据服务配置完全隔离</div>
      </div>
      <Select
        v-model:value="tenantId"
        class="w-260"
        show-search
        option-filter-prop="label"
        :options="tenantOptions"
        :loading="loading"
        @change="onTenantChange"
      />
    </div>

    <div class="row">
      <div class="row-label">
        项目
        <div class="row-hint">租户下面的第二级隔离。切换后整页会重新加载</div>
      </div>
      <Select
        v-model:value="projectId"
        class="w-260"
        show-search
        option-filter-prop="label"
        :options="projectOptions"
        :loading="loading"
        @change="onProjectChange"
      />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { Select, message } from 'ant-design-vue';
import type { SelectValue } from 'ant-design-vue/es/select';
import { listTenants, type Tenant } from '../../services/api';
import { DEFAULT_PROJECT_ID, DEFAULT_TENANT_ID, switchTo, tenantState } from '../../stores/tenant';

/**
 * 租户 / 项目切换器，「设置 › 基本信息」页专用。
 *
 * 原先是顶栏右上角两个小下拉，每一页都能改。低频操作占着最显眼的位置，
 * 误触的代价还是整页重载 + 换掉一整份数据，所以收到设置里一处。
 *
 * 仍然抽成组件而不是摊进页面：下面两条规则都不显然，值得集中在一处。
 * 切换后整页重载（见 stores/tenant.ts 的说明），这里不需要通知任何页面刷新。
 *
 * 注意：启动自检（本地存的租户在后端还在不在）<b>不在这里</b>，在
 * `stores/tenant.ts` 的 `verifyContext()`，由 main.ts 调用 —— 放在组件里的话，
 * 只有访问本页才会自检，等于没有。
 */
const tenants = ref<Tenant[]>([]);
const loading = ref(false);
const tenantId = ref<number>(tenantState.tenantId);
const projectId = ref<number>(tenantState.projectId);

/** 停用的列出来但置灰：藏起来的话，正停在上面的用户会看到一个空下拉，不知道自己在哪。 */
const tenantOptions = computed(() =>
  tenants.value.map((t) => ({
    value: t.id,
    label: t.enabled ? t.name : `${t.name}（已停用）`,
    disabled: !t.enabled,
  }))
);

const projectOptions = computed(() => {
  const hit = tenants.value.find((t) => t.id === tenantId.value);
  return (hit?.projects ?? []).map((p) => ({
    value: p.id,
    label: p.enabled ? p.name : `${p.name}（已停用）`,
    disabled: !p.enabled,
  }));
});

/** 切租户时自动挑它下面第一个启用的项目 —— 项目 id 是跨租户不通用的。 */
function onTenantChange(value: SelectValue): void {
  const id = Number(value);
  const hit = tenants.value.find((t) => t.id === id);
  const first = hit?.projects.find((p) => p.enabled) ?? hit?.projects[0];
  if (!first) {
    message.error('该租户下没有可用项目');
    tenantId.value = tenantState.tenantId;
    return;
  }
  switchTo(id, first.id, hit!.name, first.name);
}

function onProjectChange(value: SelectValue): void {
  const id = Number(value);
  const hit = tenants.value.find((t) => t.id === tenantId.value);
  const project = hit?.projects.find((p) => p.id === id);
  switchTo(tenantId.value, id, hit?.name ?? '', project?.name ?? '');
}

onMounted(async () => {
  loading.value = true;
  try {
    tenants.value = await listTenants();
    tenantId.value = tenantState.tenantId;
    projectId.value = tenantState.projectId;
  } catch (e: any) {
    // 列表加载失败不该阻塞页面本身，静默回落到默认租户的显示
    tenantId.value = DEFAULT_TENANT_ID;
    projectId.value = DEFAULT_PROJECT_ID;
    message.error('加载租户列表失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
/* 与 settings/preferences.vue 的行式布局对齐，两边看起来是同一张表单 */
.row {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  padding: 14px 0;
  border-top: 1px solid #f5f5f5;
}

.row:first-of-type {
  border-top: none;
  padding-top: 0;
}

.row-label {
  width: 260px;
  flex-shrink: 0;
  color: #1f2937;
  font-size: 14px;
}

.row-hint {
  margin-top: 2px;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.6;
}

.w-260 {
  width: 260px;
}
</style>
