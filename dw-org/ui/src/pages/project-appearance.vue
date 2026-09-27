<template>
  <div class="page">
    <PageHeader
      title="外观"
      subtitle="只作用于项目：主题、菜单栏颜色和菜单怎么摆。工作台「设置」里那套是分开的，改哪边不影响另一边。"
    />
    <AppearancePickers scope="project" :tenant-id="app.currentTenantId" />
    <MenuStylePicker class="mt" scope="project" :tenant-id="app.currentTenantId" />
  </div>
</template>

<script setup lang="ts">
import AppearancePickers from '../components/AppearancePickers.vue';
import MenuStylePicker from '../components/MenuStylePicker.vue';
import PageHeader from '../components/PageHeader.vue';
import { app } from '../stores/app';

/**
 * 项目「设置 → 外观」（PRD §4 第 7 条：设置是最后一个主菜单，内含外观）。
 *
 * <p>作用域是 `'project'`：与工作台「设置」那套（`'workbench'`）**互不影响**。
 * 项目这套是**租户级**的 —— 一个组织下所有项目共用一份，不是按项目各存一份；
 * 这与 PRD「按组织记住」一致。工作台那套另有自己的存储。
 */
</script>

<style scoped>
.mt {
  margin-top: 16px;
}
</style>
