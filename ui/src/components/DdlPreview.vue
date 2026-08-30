<template>
  <div>
    <div class="bar">
      <h3>{{ title }}</h3>
      <a-radio-group v-model:value="dialect" size="small" button-style="solid">
        <a-radio-button v-for="d in DDL_DIALECTS" :key="d.value" :value="d.value">{{ d.label }}</a-radio-button>
      </a-radio-group>
    </div>
    <p class="hint muted">同一张逻辑表，按目标引擎生成建表语句。</p>
    <SqlBlock :text="sql" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, withDefaults } from 'vue';
import { DDL_DIALECTS, renderCreateTable, type DdlDialect, type DdlTableSpec } from '@dw-ai/engine';
import SqlBlock from './SqlBlock.vue';

const props = withDefaults(
  defineProps<{
    spec: DdlTableSpec | null;
    title?: string;
  }>(),
  { title: 'DDL' }
);

const dialect = ref<DdlDialect>('hive');
const sql = computed(() => (props.spec ? renderCreateTable(props.spec, dialect.value) : ''));
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}
h3 {
  margin: 0;
  font-size: 14px;
}
.hint {
  margin: 0 0 8px;
  font-size: 12px;
}
</style>
