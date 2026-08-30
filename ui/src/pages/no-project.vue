<template>
  <div class="bare">
    <header class="bar">
      <span class="brand">智仓 DW-AI</span>
      <UserPanel menu-pos="top" />
    </header>
    <div class="msg">
      <h1>还没有可进入的项目</h1>
      <p>你尚未加入本{{ orgWord }}的任何项目。请联系管理员把你拉进项目后再来。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import UserPanel from '../components/UserPanel.vue';
import { isMultiTenant } from '../config/runtime';

const orgWord = isMultiTenant() ? '租户' : '组织';
</script>

<style scoped>
.bare {
  min-height: 100vh;
  background: var(--page);
}

.bar {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--ink);
  color: #e2e8f0;
}

.brand {
  font-size: 15px;
  font-weight: 650;
}

.msg {
  max-width: 480px;
  margin: 80px auto;
  padding: 0 24px;
}

h1 {
  margin: 0 0 8px;
  font-size: 18px;
}

p {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.6;
}
</style>
