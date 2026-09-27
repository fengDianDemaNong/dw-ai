<template>
  <section class="card">
    <h3>菜单风格</h3>
    <p class="muted">{{ hint }}</p>
    <div class="styles">
      <button
        v-for="s in options"
        :key="s.id"
        type="button"
        class="style"
        :class="{ on: appearance.menuPos === s.id }"
        @click="setMenuPos(props.scope, s.id, props.tenantId)"
      >
        <!-- 三宫格预览：原型 `pages/settings/nav.vue` 的 `.preview`，一眼看出三种摆法差在哪 -->
        <div class="preview" :class="s.id">
          <i class="bar" />
          <i class="rail" />
          <i class="body" />
        </div>
        <b>{{ s.label }}</b>
        <span>{{ s.desc }}</span>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { appearanceOf, MENU_STYLE_OPTIONS, setMenuPos, type AppearanceScope } from '../stores/prefs';

/**
 * 「菜单风格」选择器。
 *
 * <p>工作台「设置」页与项目「设置 → 外观」页共用这个组件，但**改的不是同一份**：
 * 前者 `scope="workbench"`、后者 `scope="project"`，各自有独立的存储
 * （项目那套是租户级的 —— 一个组织下所有项目共用，不是按项目各存一份）。
 * 平台后台是第三种，只有左侧/顶部。
 */
const props = defineProps<{
  scope: AppearanceScope;
  tenantId?: string | null;
}>();

const appearance = computed(() => appearanceOf(props.scope, props.tenantId));

/**
 * 只有**项目壳**能选 `drawer`：平台壳与工作台壳都没有项目壳那条顶栏，抽屉没有锚点
 * （同 `SystemLayout` 的折算；服务端也会把这两个壳的 drawer 归一成 left）。
 * 与其让用户选一个存下来也不生效的选项，不如不显示。
 */
const options = computed(() =>
  props.scope === 'project' ? MENU_STYLE_OPTIONS : MENU_STYLE_OPTIONS.filter((s) => s.id !== 'drawer')
);

const hint = computed(() => {
  if (props.scope === 'platform') return '仅平台管理：主菜单在左侧或顶部。';
  if (props.scope === 'project')
    return '只作用于项目：主菜单常驻左侧或顶部，也可以收起点左上角弹出。工作台那套是分开的。';
  return '只作用于工作台：主菜单在左侧或顶部。项目另有自己的一套。';
});
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.styles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.style {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.style.on {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 20%, transparent);
}

.style b {
  font-size: 14px;
}

.style span {
  font-size: 12px;
  line-height: 1.5;
  color: var(--muted);
}

.preview {
  position: relative;
  width: 100%;
  height: 72px;
  border-radius: 8px;
  background: #f1f5f9;
  overflow: hidden;
}

.preview i {
  position: absolute;
  background: #cbd5e1;
}

.preview .bar {
  top: 0;
  left: 0;
  right: 0;
  height: 12px;
  background: #94a3b8;
}

.preview.left .bar {
  display: none;
}

.preview.left .rail {
  top: 0;
  bottom: 0;
  left: 0;
  width: 22px;
  background: #0b1220;
}

.preview.left .body {
  top: 8px;
  left: 28px;
  right: 8px;
  bottom: 8px;
  background: #fff;
  border-radius: 4px;
}

.preview.top .rail {
  display: none;
}

.preview.top .body {
  top: 18px;
  left: 8px;
  right: 8px;
  bottom: 8px;
  background: #fff;
  border-radius: 4px;
}

/* 收起：顶栏 + 左侧一条浅色的快捷栏（不是深色侧栏，这正是它与 left 的区别）。 */
.preview.drawer .rail {
  top: 16px;
  bottom: 8px;
  left: 6px;
  width: 18px;
  background: #e2e8f0;
  border-radius: 3px;
}

.preview.drawer .body {
  top: 18px;
  left: 28px;
  right: 8px;
  bottom: 8px;
  background: #fff;
  border-radius: 4px;
}

@media (max-width: 800px) {
  .styles {
    grid-template-columns: 1fr;
  }
}
</style>
