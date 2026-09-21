<template>
  <section class="card">
    <h3>主题</h3>
    <div class="swatches">
      <button
        v-for="t in THEME_OPTIONS"
        :key="t.id"
        type="button"
        class="swatch"
        :class="{ on: appearance.theme === t.id }"
        @click="setTheme(scope, t.id, tenantId)"
      >
        <i :style="{ background: t.primary }" />
        <div>
          <b>{{ t.label }}</b>
          <span>{{ t.desc }}</span>
        </div>
      </button>
    </div>
  </section>

  <section class="card mt">
    <h3>菜单栏颜色</h3>
    <p class="muted">只改侧栏、顶栏的底色，页面主题仍用上面那套。</p>
    <div class="swatches cols-6">
      <button
        v-for="c in MENU_COLOR_OPTIONS"
        :key="c.id"
        type="button"
        class="swatch"
        :class="{ on: appearance.menuColor === c.id }"
        @click="setMenuColor(scope, c.id, tenantId)"
      >
        <i :class="{ pale: c.id === 'light' }" :style="{ background: c.color }" />
        <div>
          <b>{{ c.label }}</b>
        </div>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import {
  appearanceOf,
  MENU_COLOR_OPTIONS,
  setMenuColor,
  setTheme,
  THEME_OPTIONS,
  type AppearanceScope,
} from '../stores/prefs';

const props = defineProps<{
  scope: AppearanceScope;
  tenantId?: string | null;
}>();

const appearance = computed(() => appearanceOf(props.scope, props.tenantId));
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.muted {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.mt {
  margin-top: 16px;
}

.swatches {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.swatches.cols-6 {
  grid-template-columns: repeat(6, minmax(0, 1fr));
}

.swatch {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.swatch.on {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 20%, transparent);
}

.swatch i {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  flex-shrink: 0;
}

.swatch i.pale {
  border: 1px solid #cbd5e1;
}

.swatch b {
  display: block;
  font-size: 13px;
}

.swatch span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}

@media (max-width: 900px) {
  .swatches,
  .swatches.cols-6 {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
