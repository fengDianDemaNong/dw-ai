<template>
  <div class="page">
    <PageHeader title="概览" :subtitle="subtitle">
      <template #actions>
        <Button :loading="projectLoading || tenantLoading" @click="refresh">刷新</Button>
      </template>
      <template #help>
        <p>
          两个标签页是<b>两个统计口径</b>：「当前项目」只统计当前项目，「本租户」横跨
          该租户下的全部项目。切换租户或项目后整页重载，看到的是另一份数据。
        </p>
        <p>
          <b>字段级边</b>只算<b>当前版本</b>。历史版本的边还在库里，但那不是你在血缘图上
          看到的东西，一起算进来的话这个数字会比任何一张图上能数出来的都大。
        </p>
        <p>
          <b>元数据覆盖率给了两个数</b>：「按全名」严格比对三段全名；「按库.表」忽略首段的
          数据目录。后者明显更高，说明血缘侧按默认目录补全的前缀（<code>default.</code>）
          和元数据挂载的源端目录（<code>hive_prod.</code>）没对齐 —— 该去改数据目录，
          不是去补元数据。两个数一样低，才是元数据真的缺。
        </p>
        <p>
          <b>元数据独有</b>：结构已知、却从没在任何解析过的 SQL 里出现过的表。要么是废表，
          要么是相关 SQL 还没解析。
        </p>
        <p>
          <b>孤立表</b>：血缘目录里有，但当前版本下既没上游也没下游。通常意味着解析漏了
          什么。临时表不计入 —— 它们本来就会被穿透掉。
        </p>
        <p>
          <b>枢纽表</b>数的是<b>不同的上/下游表数</b>，不是边数 —— 边数会被宽表放大，
          一张 200 列的表随便就上千条边，那样排出来永远是同几张宽表。
        </p>
        <p>
          <b>元数据服务</b>是租户级配置，同一租户下所有项目共用一份，所以它只出现在
          「本租户」页。
        </p>
      </template>
    </PageHeader>

    <!-- 开发期假数据横幅。生产构建里 MOCK_ENABLED 恒为 false，这里永远不会出现 -->
    <Alert
      v-if="usingMock"
      banner
      type="warning"
      show-icon
      class="mb-3"
      message="当前展示的是本地假数据（VITE_STATS_MOCK=true），后端 /api/stats 尚未就绪"
    />

    <Tabs v-model:activeKey="tab" @change="(k: any) => onTabChange(String(k))">
      <!-- ============================ 当前项目 ============================ -->
      <TabPane key="project" tab="当前项目">
        <Spin :spinning="projectLoading">
          <div v-if="projectEmpty" class="page-empty">
            <div class="page-empty-text">这个项目还没有任何血缘与元数据</div>
            <Button type="primary" class="bg-[#1677ff]" @click="router.push('/lineage/analyze')">
              去解析一段 SQL
            </Button>
          </div>

          <template v-else-if="project">
            <!-- 规模 -->
            <div class="stat-row">
              <StatTile
                label="血缘表"
                :value="project.scale.lineageTables"
                :sub="`其中临时表 ${project.scale.tempTables}`"
                to="/lineage/tables"
              />
              <StatTile label="血缘字段" :value="project.scale.lineageColumns" to="/lineage/tables" />
              <StatTile
                label="字段级边"
                :value="project.scale.currentEdges"
                hint="只算当前版本。历史版本的边还在库里，但那不是图上画出来的东西"
              />
              <StatTile label="血缘版本" :value="project.scale.versions" />
              <StatTile
                label="元数据表"
                :value="project.scale.metaTables"
                :sub="`字段 ${project.scale.metaColumns.toLocaleString()}`"
                to="/lineage/meta"
              />
            </div>

            <div class="cols">
              <!-- 覆盖与质量 -->
              <div class="card col-wide">
                <div class="card-head">
                  <span class="card-title">覆盖与质量</span>
                </div>
                <RatioBar v-for="r in qualityRows" :key="r.label" v-bind="r" />
                <div class="quality-foot">
                  <a class="foot-danger" @click="scrollToLists">
                    孤立表 {{ project.quality.isolatedTables }}
                  </a>
                  <span class="muted">·</span>
                  <a @click="scrollToLists">元数据独有 {{ project.quality.metaOnlyTables }}</a>
                  <span class="muted">·</span>
                  <router-link to="/lineage/catalogs">数据目录 {{ project.scale.dataCatalogs }}</router-link>
                  <span class="muted">·</span>
                  <router-link to="/lineage/temp-rules">临时表规则 {{ project.scale.tempRules }}</router-link>
                </div>
              </div>

              <!-- 活跃度 -->
              <div class="card col-narrow">
                <div class="card-head">
                  <span class="card-title">近 {{ TREND_DAYS }} 天解析</span>
                  <span class="muted">峰值 {{ projectPeak }} 次/天</span>
                </div>
                <MiniTrend
                  :data="project.activity.trend"
                  :empty-text="`近 ${TREND_DAYS} 天没有解析记录`"
                />
                <div class="trend-foot">
                  近 7 天 <b>{{ project.activity.parses7d }}</b> 次 ·
                  近 {{ TREND_DAYS }} 天 <b>{{ project.activity.parses30d }}</b> 次 ·
                  最近 {{ formatTime(project.activity.lastParseAt) }}
                </div>
              </div>
            </div>

            <!-- 分布 -->
            <div class="card">
              <div class="card-head">
                <span class="card-title">分布</span>
                <span class="muted">每组最多 {{ DIST_LIMIT }} 项</span>
              </div>
              <div class="dist-grid">
                <div v-for="s in distSections" :key="s.title" class="dist-col">
                  <div class="dist-title">{{ s.title }}</div>
                  <RatioBar
                    v-for="row in s.rows"
                    :key="row.name ?? '(未指定)'"
                    display="count"
                    :label="row.name ?? '(未指定)'"
                    :value="row.count"
                    :total="s.max"
                  />
                  <div v-if="!s.rows.length" class="list-empty">暂无</div>
                </div>
              </div>
            </div>

            <div class="cols">
              <!-- 枢纽表 -->
              <div class="card col-wide">
                <div class="card-head">
                  <span class="card-title">枢纽表</span>
                  <span class="muted">数的是不同的上下游表数，不是边数</span>
                </div>
                <div class="hub-grid">
                  <div v-for="hub in hubSections" :key="hub.title" class="hub-col">
                    <div class="dist-title">{{ hub.title }}</div>
                    <ul v-if="hub.rows.length" class="name-list">
                      <li v-for="t in hub.rows" :key="t.tableId">
                        <a class="name" @click="router.push(`/lineage/tables/${t.tableId}`)">
                          {{ short(t.fullName) }}
                        </a>
                        <span class="muted"> · {{ t.degree }} 张</span>
                      </li>
                    </ul>
                    <div v-else class="list-empty">暂无</div>
                  </div>
                </div>
              </div>

              <!-- 同步任务 -->
              <div class="card col-narrow">
                <div class="card-head">
                  <span class="card-title">同步任务</span>
                  <span class="muted">近 {{ project.sync.days }} 天</span>
                </div>
                <div v-if="syncTotal" class="sync-row">
                  <!-- 色点之外一律带中文标签：这三个色在色觉障碍视角下几乎分不开，
                       颜色只能当辅助，不能是唯一的区分通道 -->
                  <span v-for="s in syncStatuses" :key="s.key" class="sync-item">
                    <i class="sync-dot" :style="{ background: s.color }" />
                    {{ s.label }} <b>{{ s.count }}</b>
                  </span>
                </div>
                <div v-else class="list-empty">近 {{ project.sync.days }} 天没有同步任务</div>

                <ul v-if="project.sync.recentFailures.length" class="fail-list">
                  <li v-for="f in project.sync.recentFailures" :key="f.jobId">
                    <span class="mono">{{ f.target }}</span>
                    <span class="muted"> · {{ f.failedCnt }} 张失败</span>
                    <div v-if="f.message" class="fail-msg">{{ f.message }}</div>
                  </li>
                </ul>
              </div>
            </div>

            <!-- 最近解析 + 两张清单 -->
            <div ref="listsRef" class="cols">
              <div class="card card-flush col-wide">
                <div class="card-bar">
                  <span class="card-title">最近解析</span>
                  <span class="muted">最近 {{ project.activity.recentParses.length }} 次</span>
                </div>
                <Table
                  :columns="parseCols"
                  :data-source="project.activity.recentParses"
                  row-key="versionId"
                  size="small"
                  :pagination="false"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'table'">
                      <a @click="router.push(`/lineage/tables/${record.tableId}`)">
                        {{ short(record.fullName) }}
                      </a>
                      <Tag class="ml-2">v{{ record.versionNo }}</Tag>
                      <Tag v-if="record.current" color="green">当前</Tag>
                    </template>
                    <template v-else-if="column.key === 'scale'">
                      <span class="muted">
                        {{ record.statTables }} 表 · {{ record.statColumns }} 字段 ·
                      </span>
                      {{ record.statEdges }} 边
                    </template>
                    <template v-else-if="column.key === 'createdAt'">
                      {{ formatTime(record.createdAt) }}
                    </template>
                  </template>
                </Table>
                <div v-if="!project.activity.recentParses.length" class="list-empty">
                  还没有解析过任何 SQL
                </div>
              </div>

              <!-- 两张「无血缘」清单分开列：要查的原因不一样 -->
              <div class="col-narrow">
                <div class="card card-flush">
                  <div class="card-bar">
                    <span class="card-title">元数据独有</span>
                    <Tooltip title="元数据里有结构，血缘里从没出现过。可能是废表，也可能是相关 SQL 还没解析">
                      <span class="stat-hint">?</span>
                    </Tooltip>
                  </div>
                  <ul v-if="project.lists.metaOnlyTables.length" class="name-list">
                    <li v-for="t in project.lists.metaOnlyTables" :key="t.fullName">
                      <span class="name">{{ short(t.fullName) }}</span>
                      <span v-if="t.comment" class="muted"> · {{ t.comment }}</span>
                    </li>
                  </ul>
                  <div v-else class="list-empty">没有 —— 元数据里的表都出现在血缘里</div>
                  <div v-if="truncated(project.lists.metaOnlyTables)" class="list-more">
                    只列前 {{ STATS_LIST_LIMIT }} 条，去<a @click="router.push('/lineage/meta')">元数据</a>看全部
                  </div>
                </div>

                <div class="card card-flush">
                  <div class="card-bar">
                    <span class="card-title">孤立表</span>
                    <Tooltip title="血缘里有，但当前版本下既无上游也无下游。通常是解析漏了什么。临时表不计入">
                      <span class="stat-hint">?</span>
                    </Tooltip>
                  </div>
                  <ul v-if="project.lists.isolatedTables.length" class="name-list">
                    <li v-for="t in project.lists.isolatedTables" :key="t.fullName">
                      <a class="name" @click="t.id && router.push(`/lineage/tables/${t.id}`)">
                        {{ short(t.fullName) }}
                      </a>
                      <span v-if="t.comment" class="muted"> · {{ t.comment }}</span>
                    </li>
                  </ul>
                  <div v-else class="list-empty">没有 —— 每张表都连着上游或下游</div>
                  <div v-if="truncated(project.lists.isolatedTables)" class="list-more">
                    只列前 {{ STATS_LIST_LIMIT }} 条，去<a @click="router.push('/lineage/tables')">血缘</a>看全部
                  </div>
                </div>
              </div>
            </div>
          </template>
        </Spin>
      </TabPane>

      <!-- ============================= 本租户 ============================= -->
      <TabPane key="tenant" tab="本租户">
        <Spin :spinning="tenantLoading">
          <template v-if="tenant">
            <div class="stat-row">
              <StatTile
                label="项目"
                :value="tenant.overview.projects"
                :sub="`启用 ${tenant.overview.enabledProjects} · 停用 ${tenant.overview.disabledProjects} · 空项目 ${tenant.overview.emptyProjects}`"
                to="/lineage/settings/projects"
              />
              <StatTile
                label="血缘表"
                :value="tenant.scale.lineageTables"
                :sub="`其中临时表 ${tenant.scale.tempTables}`"
              />
              <StatTile label="血缘字段" :value="tenant.scale.lineageColumns" />
              <StatTile
                label="字段级边"
                :value="tenant.scale.currentEdges"
                hint="全租户当前版本的字段级边总数"
              />
              <StatTile
                label="元数据表"
                :value="tenant.scale.metaTables"
                :sub="`字段 ${tenant.scale.metaColumns.toLocaleString()}`"
              />
            </div>

            <!-- 各项目对比：这一屏的主角 -->
            <div class="card card-flush">
              <div class="card-bar">
                <span class="card-title">各项目对比</span>
                <span class="muted">共 {{ tenant.projects.length }} 个项目</span>
              </div>
              <Table
                :columns="projectCols"
                :data-source="tenant.projects"
                row-key="projectId"
                size="small"
                :pagination="false"
                :scroll="{ x: 980 }"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'name'">
                    {{ record.name }}
                    <span class="muted mono"> {{ record.code }}</span>
                    <Tag v-if="record.projectId === currentProjectId" color="blue" class="ml-2">
                      当前
                    </Tag>
                  </template>
                  <template v-else-if="column.key === 'status'">
                    <Tag :color="record.enabled ? 'green' : undefined">
                      {{ record.enabled ? '启用' : '停用' }}
                    </Tag>
                  </template>
                  <template v-else-if="column.key === 'lineageTables'">
                    {{ record.lineageTables.toLocaleString() }}
                    <span v-if="record.tempTables" class="muted">
                      （临时 {{ record.tempTables }}）
                    </span>
                  </template>
                  <template v-else-if="column.key === 'lastParseAt'">
                    <span v-if="!record.lastParseAt" class="muted">从未</span>
                    <template v-else>{{ formatTime(record.lastParseAt) }}</template>
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <span v-if="record.projectId === currentProjectId" class="muted">—</span>
                    <!-- antd 把 bodyCell 的 record 定为 Record<string, any>，这里断言回具体类型 -->
                    <a v-else @click="switchProject(record as TenantProjectRow)">切到此项目</a>
                  </template>
                  <!-- 数字列共用一套：0 显示成灰色的「—」。空项目那一行里，
                       一串 0 比一串「—」难扫得多 -->
                  <template v-else-if="NUM_COLS.includes(String(column.key))">
                    <span :class="{ muted: !record[String(column.key)] }">
                      {{
                        record[String(column.key)]
                          ? Number(record[String(column.key)]).toLocaleString()
                          : '—'
                      }}
                    </span>
                  </template>
                </template>
              </Table>
            </div>

            <div class="cols">
              <div class="card col-wide">
                <div class="card-head">
                  <span class="card-title">全租户近 {{ TREND_DAYS }} 天解析</span>
                  <span class="muted">峰值 {{ tenantPeak }} 次/天</span>
                </div>
                <MiniTrend
                  :data="tenant.activity.trend"
                  :empty-text="`近 ${TREND_DAYS} 天全租户没有解析记录`"
                />
                <div class="trend-foot">
                  近 7 天 <b>{{ tenant.activity.parses7d }}</b> 次 ·
                  近 {{ TREND_DAYS }} 天 <b>{{ tenant.activity.parses30d }}</b> 次 ·
                  最近 {{ formatTime(tenant.activity.lastParseAt) }}
                </div>
              </div>

              <div class="card col-narrow">
                <div class="card-head">
                  <span class="card-title">元数据服务</span>
                  <span class="muted">
                    启用 {{ tenant.sources.enabled }} / {{ tenant.sources.total }}
                  </span>
                </div>
                <RatioBar
                  v-for="s in tenant.sources.byType"
                  :key="s.name"
                  display="count"
                  :label="`${s.name}（启用 ${s.enabled}）`"
                  :value="s.count"
                  :total="sourceMax"
                />
                <div v-if="!tenant.sources.byType.length" class="list-empty">
                  还没有配置元数据服务
                </div>
                <div class="quality-foot">
                  <router-link to="/lineage/settings/metadata">去配置</router-link>
                  <span class="muted">· 租户级配置，本租户所有项目共用</span>
                </div>
              </div>
            </div>
          </template>
        </Spin>
      </TabPane>
    </Tabs>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Alert, Button, Spin, Table, Tabs, Tag, Tooltip, message } from 'ant-design-vue';
import PageHeader from '../components/PageHeader/index.vue';
import StatTile from '../components/StatTile/index.vue';
import RatioBar from '../components/RatioBar/index.vue';
import MiniTrend from '../components/MiniTrend/index.vue';
import {
  STATS_LIST_LIMIT,
  TREND_DAYS,
  getProjectStats,
  getTenantStats,
} from '../services/api';
import type { ProjectStats, TenantProjectRow, TenantStats } from '../services/api';
import { displayName } from '../utils/common';
import { catalogState } from '../stores/catalog';
import { switchTo, tenantState } from '../stores/tenant';

/**
 * 概览首页。
 *
 * 改版前这一屏只有五个 count 和两张清单 —— 能回答「有多少」，回答不了「够不够好」
 * 和「别的项目怎么样」。所以拆成两个口径：「当前项目」看质量（覆盖率、孤立表、
 * 枢纽表、同步任务），「本租户」看横向对比（各项目一行，谁在动、谁是空的）。
 *
 * 口径的边界不是拍脑袋分的，是库表的隔离层级决定的：`metadata_source` 只有
 * tenant_id 没有 project_id，所以「元数据服务」只能出现在租户页；血缘与元数据
 * 两侧都带 project_id，它们在租户页的汇总是按项目求和的结果。
 *
 * 两个标签页各一个接口、各自懒加载：绝大多数人只看当前项目，进页面就把租户级
 * 那一整套（含七条 group by project_id 的聚合）也跑一遍不划算。
 *
 * 比率一律由后端给「分子 + 分母」，百分比在 RatioBar 里算 —— 分母为 0 时要显示
 * 「—」而不是 0%，这个判断只能在渲染侧做。完整契约见 docs/stats-api.md。
 */

/**
 * 开发期假数据开关。
 *
 * 两个条件缺一不可：`import.meta.env.DEV` 在生产构建里被 Vite 替换成字面量 false，
 * 整个 && 常量折叠为 false，下面 catch 里那段连同动态 import 一起被 Rollup 消除 ——
 * 产物里搜不到 statsMock 这个名字。只靠环境变量是不够的：谁在生产环境设一下就漏了。
 *
 * 刻意不做「404 才回落」：拦截器把 Spring 的 404 响应体转成 Error('Not Found')，
 * 状态码在那一步就丢了，想嗅探得去改全站唯一的请求管道，为一个开发期兜底不值当。
 */
const MOCK_ENABLED =
  import.meta.env.DEV && import.meta.env.VITE_STATS_MOCK === 'true';

/** 与后端 DIST_LIMIT 对齐，只用于「每组最多 N 项」那句提示。 */
const DIST_LIMIT = 8;

const TabPane = Tabs.TabPane;
const TABS = ['project', 'tenant'];

const router = useRouter();
const route = useRoute();

const tab = ref('project');
const project = ref<ProjectStats | null>(null);
const tenant = ref<TenantStats | null>(null);
const projectLoading = ref(false);
const tenantLoading = ref(false);
const projectLoaded = ref(false);
const tenantLoaded = ref(false);
const usingMock = ref(false);
const listsRef = ref<HTMLElement | null>(null);

const short = (name: string) => displayName(name, catalogState.defaultCatalog);

const currentProjectId = computed(() => tenantState.projectId);

const subtitle = computed(() => {
  if (tab.value === 'tenant') {
    const name = tenantState.tenantName || '当前租户';
    return tenant.value
      ? `${name} · ${tenant.value.overview.projects} 个项目 · ${tenant.value.scale.lineageTables} 张表`
      : `${name}下所有项目的汇总与对比`;
  }
  const name = tenantState.projectName || '当前项目';
  return project.value
    ? `${name} · ${project.value.scale.lineageTables} 张表 · ${project.value.scale.currentEdges} 条当前边`
    : `${name}的血缘与元数据全貌`;
});

/** 一张表都没有、一条元数据也没有，才算真的空 —— 只有血缘没元数据是常态，不是空。 */
const projectEmpty = computed(
  () =>
    !!project.value &&
    project.value.scale.lineageTables === 0 &&
    project.value.scale.metaTables === 0
);

const qualityRows = computed(() => {
  const q = project.value?.quality;
  if (!q) return [];
  return [
    {
      label: '血缘表已关联元数据（按全名）',
      value: q.metaMatchedByFullName,
      total: q.lineageTablesNonTemp,
      hint: '严格比对三段全名。与下面那条差得多，说明数据目录前缀没对齐',
    },
    {
      label: '血缘表已关联元数据（按库.表）',
      value: q.metaMatchedBySchemaTable,
      total: q.lineageTablesNonTemp,
      hint: '忽略首段数据目录，只比库名与表名',
    },
    {
      label: '元数据表已出现在血缘中',
      value: q.metaInLineage,
      total: q.metaTables,
      hint: '剩下的就是「元数据独有」那批：结构已知，但没有任何 SQL 用到过',
    },
    {
      label: '血缘表中文名填写率',
      value: q.lineageTableCommentFilled,
      total: q.lineageTables,
    },
    {
      label: '元数据表中文名填写率',
      value: q.metaTableCommentFilled,
      total: q.metaTables,
    },
    {
      label: '元数据字段中文名填写率',
      value: q.metaColumnCommentFilled,
      total: q.metaColumns,
    },
  ];
});

/** 分布四组共用一套渲染。max 用来把条长归一化到本组内部，跨组不可比也不该比。 */
const distSections = computed(() => {
  const d = project.value?.distributions;
  if (!d) return [];
  const build = (title: string, rows: { name: string | null; count: number }[]) => ({
    title,
    rows,
    max: Math.max(1, ...rows.map((r) => r.count)),
  });
  return [
    build('解析方言', d.byDbType),
    build('数据目录', d.byCatalog),
    build('元数据来源', d.byMetaSource),
    build('库', d.bySchema),
  ];
});

const hubSections = computed(() => {
  const h = project.value?.hubs;
  if (!h) return [];
  return [
    { title: '下游最多', rows: h.topDownstream },
    { title: '上游最多', rows: h.topUpstream },
  ];
});

const SYNC_META = [
  { key: 'SUCCESS', label: '成功', color: '#16a34a' },
  { key: 'PARTIAL', label: '部分成功', color: '#d48806' },
  { key: 'FAILED', label: '失败', color: '#cf1322' },
];

/** 后端只回出现过的状态，这里补齐三种 —— 「失败 0」本身就是要看的信息。 */
const syncStatuses = computed(() =>
  SYNC_META.map((m) => ({
    ...m,
    count: project.value?.sync.byStatus.find((s) => s.name === m.key)?.count ?? 0,
  }))
);

const syncTotal = computed(() => syncStatuses.value.reduce((acc, s) => acc + s.count, 0));

const projectPeak = computed(() =>
  Math.max(0, ...(project.value?.activity.trend ?? []).map((d) => d.count))
);

const tenantPeak = computed(() =>
  Math.max(0, ...(tenant.value?.activity.trend ?? []).map((d) => d.count))
);

const sourceMax = computed(() =>
  Math.max(1, ...(tenant.value?.sources.byType ?? []).map((s) => s.count))
);

const parseCols = [
  { title: '目标表', key: 'table' },
  { title: '方言', dataIndex: 'dbType', key: 'dbType', width: 90 },
  { title: '规模', key: 'scale', width: 220 },
  { title: '时间', key: 'createdAt', width: 170 },
];

const projectCols = [
  { title: '项目', key: 'name', width: 220, fixed: 'left' as const },
  { title: '状态', key: 'status', width: 80 },
  { title: '血缘表', key: 'lineageTables', width: 140 },
  { title: '血缘字段', key: 'lineageColumns', width: 100 },
  { title: '当前边', key: 'currentEdges', width: 100 },
  { title: '版本', key: 'versions', width: 80 },
  { title: '元数据表', key: 'metaTables', width: 100 },
  { title: `近 ${TREND_DAYS} 天解析`, key: 'parses30d', width: 110 },
  { title: '最近解析', key: 'lastParseAt', width: 170 },
  { title: '', key: 'action', width: 100 },
];

/** 对比表里走「千分位 + 0 显示为 —」那套渲染的列。血缘表列另有临时表后缀，不在其中。 */
const NUM_COLS = ['lineageColumns', 'currentEdges', 'versions', 'metaTables', 'parses30d'];

/** 后端截断到 STATS_LIST_LIMIT 条，取满就说明多半还有 —— 别让截断看起来像全部。 */
const truncated = (list: unknown[]) => list.length >= STATS_LIST_LIMIT;

/** 后端给的是 LocalDateTime 的 ISO 串，秒以下的精度对人没用。 */
function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

function scrollToLists() {
  listsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function switchProject(row: TenantProjectRow) {
  switchTo(tenantState.tenantId, row.projectId, tenantState.tenantName, row.name);
}

async function loadProject() {
  projectLoading.value = true;
  try {
    project.value = await getProjectStats();
    projectLoaded.value = true;
  } catch (e: any) {
    if (MOCK_ENABLED) {
      // 不区分 404 / 500 / 超时：假数据只在显式打开开关时才生效，掩盖一个真故障的
      // 代价可控，而 console 里这行加上页面顶部的黄条会让它藏不住
      console.warn('[概览] /api/stats/project 不可用，已回落到本地假数据', e);
      project.value = (await import('../services/statsMock')).projectStatsMock();
      projectLoaded.value = true;
      usingMock.value = true;
    } else {
      message.error('加载项目统计失败：' + (e?.message || e));
    }
  } finally {
    projectLoading.value = false;
  }
}

async function loadTenant() {
  tenantLoading.value = true;
  try {
    tenant.value = await getTenantStats();
    tenantLoaded.value = true;
  } catch (e: any) {
    if (MOCK_ENABLED) {
      console.warn('[概览] /api/stats/tenant 不可用，已回落到本地假数据', e);
      tenant.value = (await import('../services/statsMock')).tenantStatsMock();
      tenantLoaded.value = true;
      usingMock.value = true;
    } else {
      message.error('加载租户统计失败：' + (e?.message || e));
    }
  } finally {
    tenantLoading.value = false;
  }
}

/** 已经拉过就不再拉。切回上一个标签页时不该再等一次转圈。 */
function ensureLoaded(key: string) {
  if (key === 'tenant') {
    if (!tenantLoaded.value && !tenantLoading.value) void loadTenant();
  } else if (!projectLoaded.value && !projectLoading.value) {
    void loadProject();
  }
}

function onTabChange(key: string) {
  router.replace({ query: { ...route.query, tab: key } });
  ensureLoaded(key);
}

/**
 * 刷新把两个标签页的 loaded 标记一起清掉，但只重拉当前这个。
 *
 * 只清当前的话：在「当前项目」点了刷新，切到「本租户」看到的还是刷新前的旧数字，
 * 而按钮明明刚转过一圈 ——「刷新了但没全刷新」比压根不刷新更难排查。
 */
function refresh() {
  projectLoaded.value = false;
  tenantLoaded.value = false;
  ensureLoaded(tab.value);
}

onMounted(() => {
  const wanted = route.query.tab as string | undefined;
  if (wanted && TABS.includes(wanted)) tab.value = wanted;
  // 只拉当前标签页，另一个等激活时再说
  ensureLoaded(tab.value);
});
</script>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr);
  gap: 12px;
  align-items: start;
  margin-bottom: 12px;
}

.col-narrow {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.quality-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
  font-size: 13px;
}

.foot-danger {
  color: #cf1322;
}

.trend-foot {
  margin-top: 10px;
  font-size: 12px;
  color: #6b7280;
}

.dist-grid,
.hub-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 32px;
}

.dist-col,
.hub-col {
  min-width: 0;
}

.dist-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
}

/* 枢纽表的清单直接躺在卡片里，不需要卡片式清单那圈内边距 */
.hub-col .name-list {
  padding: 0;
}

.sync-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: #4b5563;
}

.sync-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.sync-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.fail-list {
  margin: 12px 0 0;
  padding: 12px 0 0;
  border-top: 1px solid #f0f0f0;
  list-style: none;
  font-size: 12px;
}

.fail-list li {
  margin-bottom: 8px;
}

.fail-list li:last-child {
  margin-bottom: 0;
}

.fail-msg {
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.name-list {
  margin: 0;
  padding: 8px 16px;
  list-style: none;
  max-height: 260px;
  overflow-y: auto;
}

.name-list li {
  padding: 3px 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.name {
  color: #1f2937;
}

a.name:hover {
  color: #1677ff;
}

.list-more {
  padding: 8px 16px;
  border-top: 1px solid #f0f0f0;
  font-size: 12px;
  color: #9ca3af;
}

.ml-2 {
  margin-left: 8px;
}

.mb-3 {
  margin-bottom: 12px;
}

/* 窄屏下两栏堆成一栏，分布与枢纽表的 2×2 也塌成一列 */
@media (max-width: 1200px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .dist-grid,
  .hub-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
