<template>
  <div class="page">
    <PageHeader title="血缘追踪" subtitle="开发中心不再单独维护血缘。依赖从图上来自建模里的来源与字段加工。" />
    <div class="card">
      <p>请到建模中心查看项目级依赖与字段下钻。</p>
      <a-button type="primary" @click="router.push('/app/model/lineage')">打开影响与血缘</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';

const router = useRouter();
</script>
