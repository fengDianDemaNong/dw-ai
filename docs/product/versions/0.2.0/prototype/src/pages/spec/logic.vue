<template>
  <div class="page">
    <PageHeader
      title="加工类型"
      subtitle="项目共用的五种分类。类型由产品写死，不能增删改。某列怎么算写在建模中心。"
    >
      <template #actions>
        <a-button @click="router.push('/app/spec/layers')">分层规范</a-button>
      </template>
    </PageHeader>

    <a-alert
      type="info"
      show-icon
      class="mb"
      message="这里只解释分类。字段实例（口径、来源、运算）在建模中心维护，本页不出现表名、不生成 SQL。"
    />

    <div class="grid">
      <div v-for="kind in kinds" :key="kind" :id="kind" class="card kind">
        <h3>{{ LOGIC_KIND_LABEL[kind] }}</h3>
        <p>{{ LOGIC_KIND_GUIDE[kind].definition }}</p>
        <dl>
          <dt>适用分层</dt>
          <dd>{{ LOGIC_KIND_GUIDE[kind].typicalLayers }}</dd>
          <dt>何时用</dt>
          <dd>{{ LOGIC_KIND_GUIDE[kind].when }}</dd>
          <dt>和相邻类型</dt>
          <dd>{{ LOGIC_KIND_GUIDE[kind].vs }}</dd>
          <dt>正例</dt>
          <dd><code>{{ LOGIC_KIND_GUIDE[kind].goodExample }}</code></dd>
          <dt>反例</dt>
          <dd class="muted">{{ LOGIC_KIND_GUIDE[kind].badExample }}</dd>
        </dl>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { LOGIC_KIND_GUIDE, LOGIC_KIND_LABEL, LOGIC_KIND_ORDER } from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';

const router = useRouter();
const kinds = LOGIC_KIND_ORDER;
</script>

<style scoped>
.mb {
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.kind {
  scroll-margin-top: 16px;
}
.kind h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.kind p {
  margin: 0 0 12px;
  color: var(--text);
}
dl {
  margin: 0;
}
dt {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted, #64748b);
}
dd {
  margin: 2px 0 0;
  font-size: 13px;
}
code {
  font-size: 12px;
}
</style>
