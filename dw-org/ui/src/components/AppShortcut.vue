<template>
  <!--
    快捷栏（drawer 模式专有，对应原型 `components/AppShortcut.vue`）。

    <p>抽屉是「点开一次、选一个主菜单」的导航：选完抽屉就关了，屏幕左侧空着。快捷栏补上
    这一段 —— 常驻显示**当前所在那个主菜单**的子项，换主菜单就跟着换。`<` 收起后
    整条让位给内容区，收起状态记在 localStorage。

    <p>只在 drawer 下出现（由 `SystemLayout` 控制挂载）：`left` 模式侧栏本来就常驻，
    `top` 模式的主菜单在顶栏、子项悬停即得，两者都不需要这条栏。
  -->
  <aside v-if="top" class="shortcut" :class="{ collapsed }">
    <template v-if="!collapsed">
      <div class="stitle">{{ top.label }}</div>
      <nav>
        <NavNode
          v-for="(kid, k) in top.children ?? []"
          :key="kidKey(kid, k)"
          :item="kid"
          :active-path="active"
          :depth="0"
          variant="panel"
        />
      </nav>
    </template>
    <button
      type="button"
      class="fold"
      :title="collapsed ? '展开快捷菜单' : '收起快捷菜单'"
      @click="toggle"
    >
      <RightOutlined v-if="collapsed" />
      <LeftOutlined v-else />
    </button>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { LeftOutlined, RightOutlined } from '@ant-design/icons-vue';
import type { NavItem } from '../config/nav';
import { activeNavPath, activeTopItem } from '../config/nav';
import NavNode from './NavNode.vue';

const FOLD_KEY = 'dw-ai.shortcutCollapsed';

const props = defineProps<{ items: NavItem[] }>();

const route = useRoute();
const collapsed = ref(
  typeof localStorage !== 'undefined' && localStorage.getItem(FOLD_KEY) === '1'
);
const active = computed(() => activeNavPath(route.path, props.items));

/**
 * 要显示哪一组。顶层是**叶子**（没有子菜单）时不出这条栏 —— 原型 `v-if="group && group.title"`
 * 是同一个意思：一条与侧栏完全重复的单独链接，占满 200px 换不来什么。
 */
const top = computed(() => {
  const hit = activeTopItem(route.path, props.items);
  return hit?.children?.length ? hit : null;
});

/** 与 `NavNode` 里的同名函数一致：`path` 可能是空串（目录），退到 label 再补序号。 */
function kidKey(kid: NavItem, k: number) {
  return kid.id || `${kid.path || kid.label}@${k}`;
}

function toggle() {
  collapsed.value = !collapsed.value;
  localStorage.setItem(FOLD_KEY, collapsed.value ? '1' : '0');
}
</script>

<style scoped>
.shortcut {
  position: relative;
  z-index: 2;
  width: 200px;
  flex-shrink: 0;
  padding: 16px 10px;
  background: var(--card);
  border-right: 1px solid var(--line);
  /* 收起按钮挂在右边缘外面（`right: -12px`），裁掉就没了。 */
  overflow: visible;
}

.shortcut.collapsed {
  width: 0;
  padding: 0;
  border-right: 0;
  background: transparent;
}

.stitle {
  margin: 0 8px 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--text);
}

.fold {
  position: absolute;
  top: 50%;
  right: -12px;
  z-index: 5;
  display: grid;
  place-items: center;
  width: 16px;
  height: 40px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
  border-left: 0;
  border-radius: 0 8px 8px 0;
  background: var(--card);
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
  transform: translateY(-50%);
  box-shadow: 2px 0 8px rgba(15, 23, 42, 0.06);
}

.shortcut.collapsed .fold {
  right: -16px;
  border-left: 1px solid var(--line);
}

.fold:hover {
  color: var(--primary);
}
</style>
