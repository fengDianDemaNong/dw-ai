<template>
  <aside class="sidebar">
    <router-link to="/org/workbench/projects" class="brand">
      <img src="/logo.svg" alt="" class="logo" />
      <span>
        <b>智仓</b>
        <small>DW-AI</small>
      </span>
    </router-link>

    <nav class="groups">
      <div v-for="(group, i) in groups" :key="i" class="group">
        <div v-if="group.title" class="gtitle">{{ group.title }}</div>
        <a-tooltip
          v-for="item in group.items"
          :key="item.path"
          :title="item.disabled ? item.disabledReason : ''"
        >
          <span v-if="item.disabled" class="item off">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </span>
          <a
            v-else-if="item.external"
            href="#"
            class="item"
            @click.prevent="openExternal(item.href || item.path)"
          >
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </a>
          <router-link v-else :to="item.path" class="item" :class="{ active: active === item.path }">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </router-link>
        </a-tooltip>
      </div>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { activeNavPath, buildNavGroups } from '../config/nav';
import { navIcons } from '../config/navIcons';
import { openLineageApp } from '../config/product';
import { can, hasAiCap, hasModule, projectLayerRules } from '../stores/app';

function openExternal(path: string) {
  openLineageApp(path);
}

const icons = navIcons;
const route = useRoute();
const groups = computed(() =>
  buildNavGroups(projectLayerRules.value, {
    hasModule,
    can,
    specAiDisabled: !hasAiCap('spec_design') && !hasAiCap('spec_ask'),
  })
);
const active = computed(() => activeNavPath(route.path, groups.value));
</script>

<style scoped>
.sidebar {
  width: 212px;
  flex-shrink: 0;
  height: 100vh;
  background: var(--ink);
  color: #cbd5e1;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 16px;
  color: #fff;
  text-decoration: none;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.logo {
  width: 28px;
  height: 28px;
}

.brand b {
  display: block;
  font-size: 15px;
  line-height: 1.1;
}

.brand small {
  display: block;
  font-size: 10px;
  letter-spacing: 0.12em;
  color: #67e8f9;
}

.groups {
  flex: 1;
  overflow: auto;
  padding: 10px 8px 16px;
}

.gtitle {
  margin: 12px 10px 6px;
  font-size: 11px;
  color: #64748b;
  letter-spacing: 0.08em;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  color: #94a3b8;
  text-decoration: none;
  font-size: 13px;
}

.item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: #e2e8f0;
}

.item.active {
  background: rgba(34, 211, 238, 0.12);
  color: #67e8f9;
}

.item.off {
  opacity: 0.4;
  cursor: not-allowed;
}

.ico {
  font-size: 14px;
}
</style>
