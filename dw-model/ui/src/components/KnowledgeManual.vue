<template>
  <div v-if="!engines.length" class="empty">还没有挂引擎知识库。</div>
  <div v-else class="kb">
    <div class="engines">
      <button
        v-for="e in list"
        :key="e.engine"
        type="button"
        class="eng"
        :class="{ on: current === e.engine }"
        @click="current = e.engine"
      >
        <b>{{ e.label }}</b>
        <span>{{ e.hint }}</span>
      </button>
    </div>
    <div v-if="pack" class="pack">
      <aside class="toc">
        <div class="vendor">{{ pack.vendor }} · 操作手册</div>
        <a class="docs" :href="pack.docsUrl" target="_blank" rel="noreferrer">{{ pack.docsLabel }}</a>
        <button
          v-for="a in pack.articles"
          :key="a.id"
          type="button"
          class="art"
          :class="{ on: articleId === a.id }"
          @click="articleId = a.id"
        >
          <span class="art-t">
            {{ a.title }}
            <em v-if="a.imported">导入</em>
          </span>
          <small>{{ a.summary }}</small>
        </button>
      </aside>
      <article v-if="article" class="doc">
        <h3>
          {{ article.title }}
          <em v-if="article.imported">导入</em>
        </h3>
        <a
          v-if="article.sourceUrl"
          class="src"
          :href="article.sourceUrl"
          target="_blank"
          rel="noreferrer"
        >{{ article.sourceLabel }}</a>
        <span v-else-if="article.sourceLabel" class="src muted">{{ article.sourceLabel }}</span>
        <p class="lead">{{ article.body }}</p>
        <section v-for="(sec, i) in article.sections" :key="i" class="sec">
          <h4>{{ sec.heading }}</h4>
          <p>{{ sec.body }}</p>
          <div v-if="sec.sql" class="sql-wrap">
            <div v-if="sec.sqlCaption" class="cap">{{ sec.sqlCaption }}</div>
            <SqlBlock :text="sec.sql" />
          </div>
          <p v-if="sec.note" class="note">{{ sec.note }}</p>
        </section>
        <ul v-if="article.notes?.length" class="notes">
          <li v-for="(n, i) in article.notes" :key="i">{{ n }}</li>
        </ul>
        <div v-if="manage && article.imported" class="manage">
          <a-button size="small" danger @click="removeImportedArticle(article.id, pack.engine)">删除这篇导入</a-button>
        </div>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import SqlBlock from './SqlBlock.vue';
import { mergeEngineKnowledge } from '../config/knowledge';
import type { EngineKind } from '../types';
import { removeImportedArticle, tenantKnowledgeArticles } from '../stores/app';

const props = defineProps<{ engines: EngineKind[]; manage?: boolean }>();

const list = computed(() => mergeEngineKnowledge(props.engines, tenantKnowledgeArticles.value));
const current = ref<EngineKind>(list.value[0]?.engine ?? 'hive');
const pack = computed(() => list.value.find((k) => k.engine === current.value) ?? list.value[0]);
const articleId = ref(pack.value?.articles[0]?.id ?? '');
const article = computed(() => pack.value?.articles.find((a) => a.id === articleId.value) ?? pack.value?.articles[0]);

watch(
  () => props.engines.join(','),
  () => {
    if (!props.engines.includes(current.value)) current.value = list.value[0]?.engine ?? 'hive';
  }
);

watch(
  () => pack.value?.engine,
  () => {
    articleId.value = pack.value?.articles[0]?.id ?? '';
  }
);
</script>

<style scoped>
.empty {
  color: var(--muted);
  font-size: 13px;
}

.engines {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.eng {
  text-align: left;
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
}

.eng.on {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(14, 116, 144, 0.12);
}

.eng b {
  display: block;
  font-size: 14px;
}

.eng span {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
}

.pack {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 12px;
  min-height: 420px;
}

.toc {
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: 10px;
  padding: 10px;
}

.vendor {
  margin: 4px 8px 6px;
  color: var(--muted);
  font-size: 12px;
}

.docs {
  display: block;
  margin: 0 8px 10px;
  font-size: 12px;
  color: var(--primary);
  word-break: break-all;
}

.art {
  display: block;
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  border-radius: 8px;
  padding: 8px 10px;
  cursor: pointer;
}

.art.on,
.art:hover {
  background: rgba(14, 116, 144, 0.08);
}

.art-t {
  display: flex;
  align-items: center;
  gap: 6px;
}

.art-t em,
.doc h3 em {
  font-style: normal;
  font-size: 11px;
  font-weight: 600;
  color: var(--primary);
  background: rgba(14, 116, 144, 0.1);
  border-radius: 4px;
  padding: 0 5px;
  line-height: 18px;
}

.doc h3 em {
  margin-left: 8px;
  vertical-align: middle;
}

.art small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.src.muted {
  color: var(--muted);
  text-decoration: none;
}

.manage {
  margin-top: 14px;
}

.doc {
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: 10px;
  padding: 16px 20px 20px;
}

.doc h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.src {
  display: inline-block;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--primary);
}

.lead,
.sec p {
  margin: 0 0 12px;
  color: var(--text, #334155);
  font-size: 13px;
  line-height: 1.65;
}

.sec {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.sec h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.sql-wrap {
  margin: 0 0 12px;
}

.cap {
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 12px;
}

.note {
  margin: 0 0 4px;
  padding: 8px 10px;
  border-left: 3px solid var(--primary);
  background: rgba(14, 116, 144, 0.06);
  color: var(--text, #334155);
  font-size: 12px;
  line-height: 1.55;
}

.notes {
  margin: 16px 0 0;
  padding: 10px 10px 10px 26px;
  border-radius: 8px;
  background: rgba(180, 83, 9, 0.08);
  color: var(--text, #334155);
  font-size: 12px;
  line-height: 1.6;
}

.notes li + li {
  margin-top: 4px;
}

@media (max-width: 900px) {
  .engines,
  .pack {
    grid-template-columns: 1fr;
  }
}
</style>
