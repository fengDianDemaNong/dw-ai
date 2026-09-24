<template>
  <div class="page">
    <PageHeader title="基本信息" subtitle="当前数据范围与界面偏好">
      <template #actions>
        <!-- 只管界面偏好。页面顶部现在还有「当前数据范围」，笼统写「所有设置」
             会让人以为连当前的租户/项目也一起重置了 -->
        <Popconfirm
          title="把界面偏好（主题、菜单栏位置）恢复成默认值？当前数据范围不受影响。"
          ok-text="恢复" cancel-text="取消" @confirm="reset"
        >
          <Button>恢复默认外观</Button>
        </Popconfirm>
      </template>
      <template #help>
        <p>
          这一页有两类东西，性质不一样：
        </p>
        <p>
          <b>当前数据范围</b>决定你看到哪一份血缘与元数据。改了会<b>整页重新加载</b>，
          屏幕上每一个数字都会跟着换。项目也可以从每个页面右上角的下拉直接切，
          或去「项目」页看全部项目并进入其中一个。
        </p>
        <p>
          <b>界面偏好</b>只影响观感，改完即时生效，没有「保存」按钮。
        </p>
        <p>
          两类都存在当前浏览器的 localStorage 里，<b>不跟着账号走</b> ——
          换台机器或换个浏览器都要重新设，同一个人在不同浏览器上可以有不同的偏好。
        </p>
        <p>
          血缘图的水印与缩略图不在这里，在<b>项目</b>的「设置 › 数据地图设置」——
          盖的是当前项目的血缘图，跟着导出的图片走。
        </p>
      </template>
    </PageHeader>

    <!-- ============ 当前数据范围 ============ -->
    <div v-if="hasWorkbench()" class="card">
      <div class="card-title mb-1">当前数据范围</div>
      <p class="hint mb-3">
        改的是「我在看哪一份数据」，不是「有哪些项目」—— 后者去
        <router-link :to="WORKBENCH_PAGES.projects">项目</router-link>页。
        项目也可以从每个页面右上角的下拉直接切。
      </p>
      <ProjectScopeSwitcher />
    </div>

    <!-- ============ 主题设置 ============ -->
    <div class="card">
      <div class="card-title mb-3">主题设置</div>

      <div class="row">
        <div class="row-label">
          主题
          <div class="row-hint">
            作用于「数据地图 › SQL 解析」页的代码编辑器与画布区。全站深色还没做
          </div>
        </div>
        <RadioGroup v-model:value="preferences.theme" button-style="solid">
          <RadioButton v-for="t in themes" :key="t.value" :value="t.value">{{ t.label }}</RadioButton>
        </RadioGroup>
      </div>

      <div class="row">
        <div class="row-label">
          菜单栏位置
          <div class="row-hint">
            侧边：分组层级看得见，画布拿到全部高度；顶部：矮屏幕下不占左边那一竖条
          </div>
        </div>
        <RadioGroup v-model:value="preferences.navPosition" button-style="solid">
          <RadioButton value="side">左侧</RadioButton>
          <RadioButton value="top">顶部</RadioButton>
        </RadioGroup>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { Button, Popconfirm, Radio, message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import ProjectScopeSwitcher from '../../components/ProjectScopeSwitcher/index.vue';
import { WORKBENCH_PAGES, hasWorkbench } from '../../config/pages';
import { preferences, resetAppearance } from '../../stores/preferences';
import type { ThemeName } from '../../stores/preferences';

/**
 * 基本信息：当前数据范围 + 界面偏好。工作台那一级的设置页。
 *
 * 这几项改版前藏在 SQL 解析页左上角那个齿轮弹层里，而且只存在内存中 ——
 * 点了「保存设置」也只是 emit 回父组件的 ref，刷新就没了。现在统一收在这里，
 * 由 `stores/preferences` 持久化。
 *
 * <p>血缘图的水印与缩略图<b>不在这里</b>，原来确实在这一页，后来拆去了
 * `pages/settings/map.vue`（项目级「设置 › 数据地图设置」）：那两项盖的是
 * 当前项目的血缘图，归项目更顺手。所以这一页的「恢复默认外观」也只管主题与
 * 菜单栏位置，不再顺手把水印一起清掉 —— 页面上看不见的项被按钮重置，
 * 是个说不清的意外（见 `stores/preferences` 的 `resetAppearance`）。
 *
 * 不做「保存」按钮：v-model 直接绑在 store 上，改完即时生效并落盘。
 * 主题、菜单位置这类设置，先改再看比先填表再提交自然得多。
 */
const RadioGroup = Radio.Group;
const RadioButton = Radio.Button;

const themes: { value: ThemeName; label: string }[] = [
  { value: 'vs-light', label: '浅色' },
  { value: 'vs-dark', label: '深色' },
  { value: 'vs-eyecare', label: '护眼' },
];

function reset() {
  resetAppearance();
  message.success('界面偏好已恢复默认');
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

.mb-1 {
  margin-bottom: 4px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
