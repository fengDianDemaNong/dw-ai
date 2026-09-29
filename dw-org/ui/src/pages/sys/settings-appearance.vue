<template>
  <div class="page">
    <PageHeader
      :title="scope === 'platform' ? '外观与布局' : '外观'"
      :subtitle="
        scope === 'platform'
          ? '只改平台管理后台的主题和菜单位置，不会带到任何租户的工作台或项目。'
          : '只改本组织工作台的菜单与主题。项目「设置 → 外观」是另一套，互不影响。'
      "
    />

    <AppearancePickers :scope="scope" :tenant-id="tenantId" />

    <MenuStylePicker class="mt" :scope="scope" :tenant-id="tenantId" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AppearancePickers from '../../components/AppearancePickers.vue';
import MenuStylePicker from '../../components/MenuStylePicker.vue';
import PageHeader from '../../components/PageHeader.vue';
import { app } from '../../stores/app';
import { scopeOfRoute, type AppearanceScope } from '../../stores/prefs';

/**
 * 「外观」页 —— 拆出来的三个设置子页之一，也是**唯一被两个壳共用**的一个。
 *
 * <p>平台后台（`/org/platform/settings`）与工作台（`/org/workbench/settings/appearance`）
 * 都指向本文件：外观这件事两边做的是同一件事，只是作用域不同。标题与副标题按
 * `scope` 分叉，行为完全一致 —— 拆页时别把平台侧也拆成三个子页。
 *
 * <p>作用域判据走 `scopeOfRoute(route.matched)` 而不是路径字符串，理由见该函数注释。
 */
const route = useRoute();
const scope = computed<AppearanceScope>(() => scopeOfRoute(route.matched));
/** 平台后台没有租户这一层，传 null 让它只改平台自己的那份外观。 */
const tenantId = computed(() => (scope.value === 'platform' ? null : app.currentTenantId));
</script>

<style scoped>
.mt {
  margin-top: 16px;
}
</style>
