<template>
  <div class="page">
    <PageHeader title="概况" :subtitle="`${project?.name} · 指标消费反向驱动数仓建设`">
      <template #actions>
        <a-tooltip :title="hasSpecAi ? '' : '本组织未开通此项'">
          <a-button :disabled="!hasSpecAi" @click="router.push('/app/spec/copilot')">AI 设计规范</a-button>
        </a-tooltip>
        <a-button v-if="firstModelLayer" @click="router.push(layerHref(firstModelLayer))">{{ firstModelLayer }} 总览</a-button>
        <a-button v-if="firstOds" @click="router.push('/app/model/ods-dwd')">从 ODS 建模</a-button>
        <a-button type="primary" @click="router.push('/app/service/factory')">打开指标工厂</a-button>
      </template>
    </PageHeader>

    <div class="card suite">
      <h3>套件怎么组合（本版要看的）</h3>
      <p class="muted">
        产品进程在平台注册。调度和数仓引擎是租户自己的计算资源：星河已接 DolphinScheduler，启航还没配。
        星河开通仓建设 / 数据地图 / 质量 / 服务，本组织关掉了服务；数据地图仅持有角色可见，质量仅管理员可见。启航开通仓建设 + 数据地图。
      </p>
      <a-space>
        <a-button @click="router.push('/app/members')">看分产品角色</a-button>
        <a-button type="primary" @click="router.push('/app/map/search')">打开数据地图</a-button>
      </a-space>
    </div>

    <div class="stat-grid">
      <div class="stat">
        <div class="k">主题域</div>
        <div class="v">{{ domains.length }}</div>
        <div class="hint">按业务过程划分</div>
      </div>
      <div class="stat">
        <div class="k">已发布模型</div>
        <div class="v">{{ modeled.length }}</div>
        <div class="hint">不含 ODS</div>
      </div>
      <div class="stat">
        <div class="k">已发布指标</div>
        <div class="v">{{ metrics.filter((m) => m.status === 'published').length }}</div>
        <div class="hint">原子 / 派生 / 复合</div>
      </div>
      <div class="stat">
        <div class="k">待审物化</div>
        <div class="v">{{ recs.filter((r) => r.status === 'pending').length }}</div>
        <div class="hint">查询簇综合评分</div>
      </div>
    </div>

    <div class="grid2">
      <div class="card">
        <h3>数据闭环</h3>
        <div class="loop">
          <span>业务系统</span><i>→</i>
          <span>ODS</span><i>→</i>
          <span>DWD</span><i>→</i>
          <span>DWS</span><i>→</i>
          <span>指标工厂</span><i>→</i>
          <span>查询日志</span><i>→</i>
          <span>沉淀推荐</span>
        </div>
        <p class="muted mt">
          用户在指标工厂取数，系统采集 SQL 指纹并聚类。高频高成本模式会反向推荐新建或扩展 DWS，
          审核灰度后自动路由，用户无感知加速。取数会按分层对外策略与数据等级拦截或走审批。
        </p>
      </div>
      <div class="card">
        <h3>最近任务</h3>
        <a-table :data-source="jobs.slice(0, 5)" :columns="jobCols" :pagination="false" size="small" row-key="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-tag :color="record.status === 'success' ? 'green' : 'red'">{{ record.status }}</a-tag>
            </template>
          </template>
        </a-table>
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
        <li v-else>已有 {{ modeled.length }} 张模型表，可用指标工厂取数（受分层 serve 与等级拦截）。</li>
        <li v-if="recs.length">到沉淀引擎查看查询簇，把评分较高的推荐灰度上线。</li>
        <li v-else>模型发布并产生查询后，沉淀引擎会给出物化推荐。</li>
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
  projectJobs,
  projectMetrics,
  projectRecs,
  projectTables,
} from '../stores/app';

const router = useRouter();
const hasSpecAi = computed(() => hasAiCap('spec_design') || hasAiCap('spec_ask'));
const project = currentProject;
const domains = projectDomains;
const tables = projectTables;
const metrics = projectMetrics;
const recs = projectRecs;
const jobs = projectJobs;
const modeled = computed(() => tables.value.filter((t) => t.layer !== 'ODS'));
const firstOds = computed(() => tables.value.find((t) => t.layer === 'ODS'));
const firstModelLayer = computed(() => modeled.value[0]?.layer ?? (tables.value.some((t) => t.layer === 'DWD') ? 'DWD' : undefined));

const jobCols = [
  { title: '任务', dataIndex: 'name', key: 'name' },
  { title: '引擎', dataIndex: 'engine', key: 'engine', width: 120 },
  { title: '状态', key: 'status', width: 100 },
  { title: '最近运行', dataIndex: 'lastRun', key: 'lastRun', width: 160 },
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
.suite {
  margin-bottom: 16px;
}
.steps {
  margin: 0;
  padding-left: 18px;
  color: #334155;
  line-height: 1.9;
}
</style>
