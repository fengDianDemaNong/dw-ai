<template>
  <div class="scope-rows">
    <div class="row">
      <div class="row-label">
        项目
        <div class="row-hint">切换后整页会重新加载</div>
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
import { listProjects, type Project } from '../../services/api';
import { DEFAULT_PROJECT_ID, switchTo, tenantState } from '../../stores/tenant';

/**
 * 项目切换器，「设置 › 基本信息」页专用。
 *
 * <p>原先这里是「租户 + 项目」两个下拉（组件那时叫 TenantSwitcher）。
 * 租户维度从本进程的管理面移除之后，那一行整个去掉了，只剩项目这一级。
 *
 * <p>仍然抽成组件而不是摊进页面：下面是两条不显然的规则，值得集中在一处。
 * 切换后整页重载（见 `stores/tenant.ts` 的说明），所以这里不需要通知任何页面刷新。
 *
 * <p>取项目用 {@code tenantState.tenantId} 而不是写死 1：standard 下它在启动时已被
 * {@code pinDefaultTenant()} 钉成默认租户，两者等价；但哪天项目页在别的模式下也开放，
 * 这里不用改。
 *
 * <p>注意：启动自检（本地存的项目在后端还在不在）<b>不在这里</b>，在
 * `stores/tenant.ts` 的 `verifyContext()`，由 main.ts 调用 —— 放在组件里的话，
 * 只有访问本页才会自检，等于没有。
 */
const projects = ref<Project[]>([]);
const loading = ref(false);
const projectId = ref<number>(tenantState.projectId);

/** 停用的列出来但置灰：藏起来的话，正停在上面的用户会看到一个空下拉，不知道自己在哪。 */
const projectOptions = computed(() =>
  projects.value.map((p) => ({
    value: p.id,
    label: p.enabled ? p.name : `${p.name}（已停用）`,
    disabled: !p.enabled,
  }))
);

function onProjectChange(value: SelectValue): void {
  const id = Number(value);
  const hit = projects.value.find((p) => p.id === id);
  switchTo(tenantState.tenantId, id, tenantState.tenantName, hit?.name ?? '');
}

onMounted(async () => {
  loading.value = true;
  try {
    projects.value = await listProjects(tenantState.tenantId);
    projectId.value = tenantState.projectId;
  } catch (e: any) {
    // 列表加载失败不该阻塞页面本身，静默回落到默认项目的显示
    projectId.value = DEFAULT_PROJECT_ID;
    message.error('加载项目列表失败：' + (e?.message || e));
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
