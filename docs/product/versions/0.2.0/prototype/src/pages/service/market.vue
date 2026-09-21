<template>
  <div class="page">
    <PageHeader title="指标市场" subtitle="好的指标可以被打分、收藏、复用，形成内部数据资产。" />
    <div class="grid">
      <div v-for="m in ranked" :key="m.id" class="card item">
        <div class="row">
          <h3>{{ m.name }}</h3>
          <a-rate :value="Math.round(m.score)" disabled allow-half />
        </div>
        <p>{{ m.definition }}</p>
        <div class="meta">
          <a-tag>{{ m.type }}</a-tag>
          <span>{{ m.favorites }} 收藏</span>
          <span>{{ m.owner }}</span>
          <span>{{ m.version }}</span>
        </div>
        <div class="btns">
          <a-button size="small" @click="favoriteMetric(m.id)">收藏</a-button>
          <a-button size="small" type="primary" @click="router.push('/app/service/factory')">复用到工厂</a-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import { favoriteMetric, projectMetrics } from '../../stores/app';

const router = useRouter();
const ranked = computed(() => [...projectMetrics.value].sort((a, b) => b.score - a.score || b.favorites - a.favorites));
</script>

<style scoped>
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.item h3 { margin: 0; font-size: 16px; }
.row { display: flex; justify-content: space-between; align-items: center; }
p { color: #64748b; min-height: 40px; }
.meta { display: flex; gap: 10px; align-items: center; font-size: 12px; color: #64748b; margin-bottom: 10px; }
.btns { display: flex; gap: 8px; }
</style>
