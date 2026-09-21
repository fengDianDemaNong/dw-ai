<template>
  <div class="page">
    <PageHeader title="外观" subtitle="本组织项目的主题、菜单栏颜色和菜单怎么摆。收起是点左上角弹出；进入某个主菜单后，左侧只留它的快捷项。" />
    <AppearancePickers scope="tenant" :tenant-id="tenant?.id" />
    <section class="card mt">
      <h3>菜单风格</h3>
      <div class="styles">
        <button
          v-for="s in MENU_STYLE_OPTIONS"
          :key="s.id"
          type="button"
          class="style"
          :class="{ on: current === s.id }"
          @click="pick(s.id)"
        >
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
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import AppearancePickers from '../../components/AppearancePickers.vue';
import PageHeader from '../../components/PageHeader.vue';
import { currentTenant } from '../../stores/app';
import { appearanceOf, MENU_STYLE_OPTIONS, setMenuPos, type MenuPos } from '../../stores/prefs';

const tenant = currentTenant;
const current = computed(() => appearanceOf('tenant', tenant.value?.id).menuPos);

function pick(pos: MenuPos) {
  setMenuPos('tenant', pos, tenant.value?.id);
}
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.mt {
  margin-top: 16px;
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
