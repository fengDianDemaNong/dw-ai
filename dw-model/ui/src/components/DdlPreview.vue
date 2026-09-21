<template>
  <div>
    <div class="bar">
      <h3 v-if="title">{{ title }}</h3>
      <div class="toggles">
        <a-radio-group v-if="showDml" v-model:value="mode" size="small" button-style="solid">
          <a-radio-button value="ddl">建表 DDL</a-radio-button>
          <a-radio-button value="dml">加工 DML</a-radio-button>
        </a-radio-group>
        <a-radio-group v-if="mode === 'ddl'" v-model:value="dialect" size="small" button-style="solid">
          <a-radio-button v-for="d in DDL_DIALECTS" :key="d.value" :value="d.value">{{ d.label }}</a-radio-button>
        </a-radio-group>
      </div>
    </div>
    <p class="hint muted">{{ hint }}</p>
    <SqlBlock :text="sql" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { DDL_DIALECTS, renderCreateTable, type DdlDialect, type DdlTableSpec } from '@dw-ai/engine';
import SqlBlock from './SqlBlock.vue';

const props = withDefaults(
  defineProps<{
    spec: DdlTableSpec | null;
    dmlSql?: string;
    title?: string;
  }>(),
  { title: '逻辑 SQL' }
);

const dialect = ref<DdlDialect>('hive');
const mode = ref<'ddl' | 'dml'>('ddl');
const showDml = computed(() => props.dmlSql !== undefined);
const hint = computed(() => {
  if (mode.value === 'dml') {
    return '由字段加工生成插入/加工语句（一份逻辑 SQL）。真源仍是表结构与字段定义。';
  }
  return showDml.value
    ? '按目标引擎生成建表语句。加工 DML 在另一页签。真源仍是表结构与字段定义。'
    : '同一张逻辑表，按目标引擎生成建表语句。';
});
const sql = computed(() => {
  if (mode.value === 'dml') return props.dmlSql || '';
  return props.spec ? renderCreateTable(props.spec, dialect.value) : '';
});

watch(showDml, (on) => {
  if (!on) mode.value = 'ddl';
});
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.bar:not(:has(h3)) {
  justify-content: flex-end;
}
.toggles {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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
