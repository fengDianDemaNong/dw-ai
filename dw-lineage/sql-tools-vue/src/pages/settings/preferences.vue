<template>
  <div class="page">
    <PageHeader :title="embed ? '数据地图设置' : '基本信息'" :subtitle="embed ? '血缘图水印与缩略图' : '当前数据范围与界面偏好'">
      <template #actions>
        <!-- 只管界面偏好。页面顶部现在还有「当前数据范围」，笼统写「所有设置」
             会让人以为连租户也一起重置了 -->
        <Popconfirm
          :title="embed
            ? '把血缘图水印和缩略图恢复成默认值？'
            : '把界面偏好（主题、菜单栏位置、水印、缩略图）恢复成默认值？当前数据范围不受影响。'"
          ok-text="恢复" cancel-text="取消" @confirm="reset"
        >
          <Button>{{ embed ? '恢复默认' : '恢复默认外观' }}</Button>
        </Popconfirm>
      </template>
      <template #help>
        <template v-if="embed">
          <p>水印叠在血缘图上，导出图片时一起带走。缩略图是画布右下角的全图定位窗。</p>
          <p>改完即时生效，没有单独的保存按钮。</p>
        </template>
        <template v-else>
          <p>
            这一页有两类东西，性质不一样：
          </p>
          <p>
            <b>当前数据范围</b>决定你看到哪一份血缘与元数据。改了会<b>整页重新加载</b>，
            屏幕上每一个数字都会跟着换。项目也可以从每个页面右上角的下拉直接切；
            改租户仍在这一页，避免误触换掉整份数据。
          </p>
          <p>
            <b>界面偏好</b>只影响观感，改完即时生效，没有「保存」按钮。
          </p>
          <p>
            两类都存在当前浏览器的 localStorage 里，不跟着账号走（当前也还没有账号），
            换台机器或换个浏览器要重新设。
          </p>
        </template>
      </template>
    </PageHeader>

    <!-- ============ 当前数据范围 ============ -->
    <div v-if="!embed && showLocalTenantSettings()" class="card">
      <div class="card-title mb-1">当前数据范围</div>
      <p class="hint mb-3">
        改的是「我在看哪一份数据」，不是「有哪些租户和项目」—— 后者去
        <router-link to="/lineage/settings/tenants">设置 › 租户</router-link> 和
        <router-link to="/lineage/settings/projects">设置 › 项目</router-link>。
      </p>
      <TenantSwitcher />
    </div>

    <!-- ============ 主题设置 ============ -->
    <div v-if="!embed" class="card">
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

    <!-- ============ 血缘图 ============ -->
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
        <Input v-model:value="preferences.watermark" class="w-320" allow-clear
               placeholder="例如 内部资料 / 你的工号" />
      </div>

      <div class="row">
        <div class="row-label">
          缩略图
          <div class="row-hint">画布右下角的全图缩略窗，图很大时用来定位</div>
        </div>
        <Switch v-model:checked="preferences.showMinimap" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { Button, Input, Popconfirm, Radio, Switch, message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import TenantSwitcher from '../../components/TenantSwitcher/index.vue';
import { isEmbed } from '../../config/runtime';
import { showLocalTenantSettings } from '../../config/pages';
import { preferences, resetMapPreferences, resetPreferences } from '../../stores/preferences';
import type { ThemeName } from '../../stores/preferences';

/**
 * 界面偏好设置。
 *
 * 这几项改版前藏在 SQL 解析页左上角那个齿轮弹层里，而且只存在内存中 ——
 * 点了「保存设置」也只是 emit 回父组件的 ref，刷新就没了。现在统一收在这里，
 * 由 `stores/preferences` 持久化。
 *
 * 不做「保存」按钮：v-model 直接绑在 store 上，改完即时生效并落盘。
 * 主题、菜单位置这类设置，先改再看比先填表再提交自然得多。
 */
const RadioGroup = Radio.Group;
const RadioButton = Radio.Button;
const embed = isEmbed();

const themes: { value: ThemeName; label: string }[] = [
  { value: 'vs-light', label: '浅色' },
  { value: 'vs-dark', label: '深色' },
  { value: 'vs-eyecare', label: '护眼' },
];

function reset() {
  if (embed) {
    resetMapPreferences();
    message.success('血缘图设置已恢复默认');
    return;
  }
  resetPreferences();
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

.w-320 {
  width: 320px;
}

.mb-1 {
  margin-bottom: 4px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
