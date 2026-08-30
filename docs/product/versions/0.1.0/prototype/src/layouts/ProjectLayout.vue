<template>
  <div class="shell">
    <AppSidebar />
    <div class="main">
      <header class="top">
        <div class="crumb">
          <a @click="back">{{ tenant?.name }}</a>
          <span class="sep">/</span>
          <span>{{ project?.name }}</span>
        </div>
        <div class="right">
          <a-tag color="gold">产品原型 0.1.0</a-tag>
          <a-tag color="cyan">{{ user }}</a-tag>
          <a-button size="small" @click="resetDemo">重置演示</a-button>
        </div>
      </header>
      <div class="content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import AppSidebar from '../components/AppSidebar.vue';
import { currentProject, currentTenant, leaveProject, resetDemo } from '../stores/app';
import { app } from '../stores/app';

const router = useRouter();
const tenant = currentTenant;
const project = currentProject;
const user = app.currentUser;

function back() {
  leaveProject();
  router.push('/projects');
}
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.top {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid var(--line);
}

.crumb {
  font-size: 13px;
  color: #334155;
}

.crumb a {
  color: #0e7490;
  cursor: pointer;
}

.sep {
  margin: 0 8px;
  color: #cbd5e1;
}

.right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
