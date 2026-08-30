<template>
  <div class="page">
    <PageHeader title="无权访问" subtitle="当前角色或租户模块未开通此能力。" />
    <div class="card">
      <p class="muted">侧栏只显示你能进的入口。如需写规范、发布模型或打开其它模块，请让项目管理员改角色，或开通对应模块。</p>
      <a-button type="primary" @click="router.push('/w')">回工作台</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import PageHeader from '../components/PageHeader.vue';

const router = useRouter();
</script>
