<template>
  <div class="page">
    <PageHeader title="数据地图设置" subtitle="血缘图水印与缩略图">
      <template #actions>
        <Popconfirm
          title="把血缘图水印和缩略图恢复成默认值？"
          ok-text="恢复" cancel-text="取消" @confirm="reset"
        >
          <Button>恢复默认</Button>
        </Popconfirm>
      </template>
      <template #help>
        <p>水印叠在血缘图上，导出图片时一起带走。缩略图是画布右下角的全图定位窗。</p>
        <p>
          两项<b>按项目各存一份</b>，存在当前浏览器的 localStorage 里（不跟着账号走）——
          在 A 项目改的水印，切到 B 项目不会跟着过去；换台机器或换个浏览器则都要重新设。
        </p>
        <p>改完即时生效，没有单独的保存按钮。</p>
      </template>
    </PageHeader>

    <div class="card">
      <div class="card-title mb-3">血缘图</div>
      <p class="hint mb-3">
        「数据地图 › SQL 解析」和「数据地图 › 血缘」详情页画的是同一个画布组件，
        这两项对两处都生效 —— 只在其中一处加水印，导出的图片就有一半没有标记。
      </p>

      <div class="row">
        <div class="row-label">
          水印
          <div class="row-hint">
            叠在血缘图上的文字，导出图片时一起带走。留空表示不加
          </div>
        </div>
        <Input v-model:value="mapPreferences.watermark" class="w-320" allow-clear
               placeholder="例如 内部资料 / 你的工号" />
      </div>

      <div class="row">
        <div class="row-label">
          缩略图
          <div class="row-hint">画布右下角的全图缩略窗，图很大时用来定位</div>
        </div>
        <Switch v-model:checked="mapPreferences.showMinimap" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { Button, Input, Popconfirm, Switch, message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import { mapPreferences, resetMapPreferences } from '../../stores/preferences';

/**
 * 数据地图设置：血缘图的水印与缩略图。
 *
 * <p>从「基本信息」页里拆出来的，挂在**项目级**菜单的「设置」下 ——
 * 盖的是当前这个项目的血缘图，跟着导出的图片走，放在项目里比放在工作台顺手。
 * 存在 {@link mapPreferences} 里的值也是**按项目各一份**：在 A 项目设的水印，
 * 切到 B 项目不会跟过去。
 *
 * <p>同一个组件就是 dw-model 的「数据地图设置」页：仓建设那边把
 * `/lineage/settings/map` 通过 postMessage 推进 iframe（见 `config/pages.ts`
 * 的 `PROJECT_SETTINGS_PAGES`），所以这页<b>不区分是否被嵌入</b> ——
 * 两种入口看到的是同一张表单，以前嵌与非嵌两套标题/按钮文案的写法也就没必要了。
 *
 * <p>不做「保存」按钮：v-model 直接绑在 store 的读写访问器上，改完即时生效并落盘
 * （落盘按当前项目分键，见 `stores/preferences` 的 `MAP_KEY`）。
 */
function reset(): void {
  resetMapPreferences();
  message.success('血缘图设置已恢复默认');
}
</script>

<style scoped>
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

.w-320 {
  width: 320px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
