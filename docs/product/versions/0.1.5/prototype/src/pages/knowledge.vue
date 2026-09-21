<template>
  <div class="page">
    <PageHeader
      title="知识库"
      :subtitle="
        engines.length
          ? '本项目已开通的引擎手册（含租户导入的补充篇）。建表 / 改表以各引擎为准，和规范中心的命名约定分开看。'
          : '本项目还没有挂引擎知识库。请租户管理员到工作台「知识库」勾选 Hive / Spark / ClickHouse / Doris。'
      "
    />
    <KnowledgeManual :engines="engines" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '../components/PageHeader.vue';
import KnowledgeManual from '../components/KnowledgeManual.vue';
import { currentProject } from '../stores/app';
import type { EngineKind } from '../types';

const engines = computed<EngineKind[]>(() => [...(currentProject.value?.engines ?? [])]);
</script>
