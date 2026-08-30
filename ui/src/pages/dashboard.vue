<template>
  <div class="page">
    <PageHeader title="概况" :subtitle="`${project?.name} · 规范中心与建模中心`">
      <template #actions>
        <a-tooltip :title="hasSpecAi ? '' : '本组织未开通此项'">
          <a-button :disabled="!hasSpecAi" @click="router.push('/w/spec/copilot')">AI 设计规范</a-button>
        </a-tooltip>
        <a-button v-if="firstModelLayer" @click="router.push(layerHref(firstModelLayer))">{{ firstModelLayer }} 总览</a-button>
        <a-button v-if="firstOds" type="primary" @click="router.push('/w/model/ods-dwd')">从 ODS 建模</a-button>
      </template>
    </PageHeader>

    <div class="stat-grid">
      <div class="stat">
        <div class="k">主题域</div>
        <div class="v">{{ domains.length }}</div>
        <div class="hint">按业务过程划分</div>
      </div>
      <div class="stat">
        <div class="k">分层</div>
        <div class="v">{{ layers.length }}</div>
        <div class="hint">规范中的数仓分层</div>
      </div>
      <div class="stat">
        <div class="k">词根</div>
        <div class="v">{{ roots.length }}</div>
        <div class="hint">命名词根库</div>
      </div>
      <div class="stat">
        <div class="k">已发布模型</div>
        <div class="v">{{ modeled.length }}</div>
        <div class="hint">不含 ODS</div>
      </div>
    </div>

    <div class="grid2">
      <div class="card">
        <h3>建设路径</h3>
        <div class="loop">
          <span>规范</span><i>→</i>
          <span>ODS</span><i>→</i>
          <span>DWD</span><i>→</i>
          <span>DWS</span>
        </div>
        <p class="muted mt">
          先在规范中心定主题域、分层、等级与词根，再在建模中心登记 ODS、生成并审核发布下一层逻辑表。
        </p>
      </div>
      <div class="card">
        <h3>最近模型表</h3>
        <a-table :data-source="recentTables" :columns="tableCols" :pagination="false" size="small" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-tag :color="record.status === 'published' ? 'green' : 'default'">{{ record.status }}</a-tag>
            </template>
          </template>
        </a-table>
        <p v-if="!recentTables.length" class="muted">还没有表，可从分层总览或 ODS 生成开始。</p>
      </div>
    </div>

    <div class="card mt">
      <h3>建议下一步</h3>
      <ol class="steps">
        <li v-if="!domains.length && canWriteSpec">
          用「AI 设计规范」生成主题域 / 分层 / 等级 / 词根并同步，或新建项目时勾选「导入通用规范模板」。
        </li>
        <li v-else-if="!domains.length">规范尚未就绪，请项目管理员先设计主题域与分层。</li>
        <li v-else-if="canWriteSpec">已有 {{ domains.length }} 个主题域，可到规范中心调整分层对外策略与数据等级。</li>
        <li v-else>已有 {{ domains.length }} 个主题域，可到规范中心查看；作业请到建模中心。</li>
        <li v-if="!modeled.length && firstOds">
          到建模中心对 <code>{{ firstOds.name }}</code> 生成下一层草案并审核发布。
        </li>
        <li v-else-if="!modeled.length">先登记 ODS 源表，再到对应分层页新增或从上一层生成。</li>
        <li v-else>已有 {{ modeled.length }} 张模型表，可继续按分层补全或用每层 AI 设计表。</li>
      </ol>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '../components/PageHeader.vue';
import { layerHref } from '../config/layers';
import {
  canWriteSpec,
  currentProject,
  hasAiCap,
  projectDomains,
  projectLayerRules,
  projectRoots,
  projectTables,
} from '../stores/app';

const hasSpecAi = computed(() => hasAiCap('spec_design') || hasAiCap('spec_ask'));

const router = useRouter();
const project = currentProject;
const domains = projectDomains;
const layers = projectLayerRules;
const roots = projectRoots;
const tables = projectTables;
const modeled = computed(() => tables.value.filter((t) => t.layer !== 'ODS'));
const firstOds = computed(() => tables.value.find((t) => t.layer === 'ODS'));
const firstModelLayer = computed(() => modeled.value[0]?.layer ?? (tables.value.some((t) => t.layer === 'DWD') ? 'DWD' : undefined));
const recentTables = computed(() => [...tables.value].slice(-5).reverse());

const tableCols = [
  { title: '表', dataIndex: 'name', key: 'name' },
  { title: '分层', dataIndex: 'layer', key: 'layer', width: 80 },
  { title: '状态', key: 'status', width: 100 },
];
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1.1fr;
  gap: 12px;
}
.loop {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 13px;
}
.loop span {
  background: #ecfeff;
  color: #0e7490;
  padding: 4px 8px;
  border-radius: 6px;
}
.loop i {
  color: #94a3b8;
  font-style: normal;
}
.mt {
  margin-top: 12px;
}
.steps {
  margin: 0;
  padding-left: 18px;
  color: #334155;
  line-height: 1.9;
}
</style>
