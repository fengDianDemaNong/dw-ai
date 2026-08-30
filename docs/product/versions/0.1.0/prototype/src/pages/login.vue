<template>
  <div class="login">
    <div class="panel">
      <div class="logo-row">
        <img src="/logo.svg" alt="" />
        <div>
          <h1>智仓 DW-AI · 产品原型 0.1.0</h1>
          <p>可点击交互。与实现仓库分离，改这里不影响功能迭代窗口。</p>
        </div>
      </div>

      <p class="hint">选择租户进入。租户内创建项目后，才能使用建模、指标与沉淀能力。</p>

      <div class="tenants">
        <button
          v-for="t in app.tenants"
          :key="t.id"
          class="tenant"
          :class="{ on: selected === t.id }"
          @click="selected = t.id"
        >
          <div class="name">{{ t.name }}</div>
          <div class="meta">{{ t.code }} · Owner {{ t.owner }}</div>
          <div class="meta">{{ count(t.id) }} 个项目</div>
        </button>
      </div>

      <a-button type="primary" size="large" block :disabled="!selected" @click="enter">
        进入租户
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { app, switchTenant } from '../stores/app';

const router = useRouter();
const selected = ref(app.currentTenantId);

function count(id: string) {
  return app.projects.filter((p) => p.tenantId === id).length;
}

function enter() {
  switchTenant(selected.value);
  router.push('/projects');
}
</script>

<style scoped>
.login {
  min-height: 100%;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 500px at 10% -10%, rgba(34, 211, 238, 0.18), transparent),
    #070b14;
}

.panel {
  width: 560px;
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.35);
}

.logo-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.logo-row img {
  width: 40px;
  height: 40px;
}

h1 {
  margin: 0;
  font-size: 20px;
}

.logo-row p,
.hint {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}

.hint {
  margin: 0 0 16px;
}

.tenants {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 16px;
}

.tenant {
  text-align: left;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
}

.tenant.on {
  border-color: #0e7490;
  box-shadow: 0 0 0 3px rgba(14, 116, 144, 0.15);
}

.name {
  font-weight: 650;
}

.meta {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}
</style>
