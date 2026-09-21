<template>
  <div v-if="visible" class="project-switcher" title="切换项目后整页会重新加载">
    <ProjectOutlined class="switcher-icon" />
    <Select
      v-model:value="projectId"
      class="switcher-select"
      size="small"
      :bordered="false"
      show-search
      option-filter-prop="label"
      :options="options"
      :loading="loading"
      :dropdown-match-select-width="220"
      placeholder="选择项目"
      @change="onChange"
    />
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { Select, message } from 'ant-design-vue';
import { ProjectOutlined } from '@ant-design/icons-vue';
import type { SelectValue } from 'ant-design-vue/es/select';
import { listProjects, type Project } from '../../services/api';
import { setNames, switchTo, tenantState } from '../../stores/tenant';

/**
 * 顶栏右上角的项目切换。
 *
 * 只切当前租户下的项目；改租户仍走「设置 › 基本信息」。
 * 当前租户只有一个项目时不渲染——没有可选对象，占着右上角没有意义。
 * 切换走 {@code switchTo}，整页重载，避免各页残留上一份项目的数据。
 */
const projects = ref<Project[]>([]);
const loading = ref(true);
const projectId = ref<number>(tenantState.projectId);

const visible = computed(() => !loading.value && projects.value.length > 1);

const options = computed(() => {
  const list = projects.value.map((p) => ({
    value: p.id,
    label: p.enabled ? p.name : `${p.name}（已停用）`,
    disabled: !p.enabled,
  }));
  // 列表尚未回来或请求失败时，至少用当前上下文的名字，避免下拉里只剩一个数字 id
  if (!list.some((o) => o.value === projectId.value)) {
    const name = tenantState.projectName || `项目 ${projectId.value}`;
    list.unshift({ value: projectId.value, label: name, disabled: false });
  }
  return list;
});

function onChange(value: SelectValue): void {
  const id = Number(value);
  const project = projects.value.find((p) => p.id === id);
  if (!project) {
    projectId.value = tenantState.projectId;
    return;
  }
  switchTo(tenantState.tenantId, id, tenantState.tenantName, project.name);
}

onMounted(async () => {
  loading.value = true;
  try {
    projects.value = await listProjects(tenantState.tenantId);
    projectId.value = tenantState.projectId;
    if (!tenantState.projectName) {
      const current = projects.value.find((p) => p.id === projectId.value);
      if (current) setNames(tenantState.tenantName, current.name);
    }
  } catch (e: any) {
    message.error('加载项目列表失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.project-switcher {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  max-width: 240px;
  padding: 0 4px 0 8px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  background: #fafafa;
}

.switcher-icon {
  color: #6b7280;
  font-size: 14px;
}

.switcher-select {
  min-width: 120px;
  max-width: 200px;
}

.switcher-select :deep(.ant-select-selector) {
  padding-right: 20px !important;
}

.switcher-select :deep(.ant-select-selection-item) {
  font-size: 13px;
  color: #1f2937;
}
</style>
