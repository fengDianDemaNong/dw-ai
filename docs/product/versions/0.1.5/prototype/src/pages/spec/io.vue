<template>
  <div class="page">
    <PageHeader
      title="导入导出"
      subtitle="JSON 迁移包用于项目/环境之间整包搬迁；Excel 方便改主题域、分层、词根、数据等级后再导回来。"
    />
    <SpecReadonlyTip />

    <div class="stat-grid">
      <div class="stat">
        <div class="k">主题域</div>
        <div class="v">{{ domains.length }}</div>
      </div>
      <div class="stat">
        <div class="k">分层</div>
        <div class="v">{{ layers.length }}</div>
      </div>
      <div class="stat">
        <div class="k">词根</div>
        <div class="v">{{ roots.length }}</div>
      </div>
      <div class="stat">
        <div class="k">数据等级</div>
        <div class="v">{{ grades.length }}</div>
      </div>
    </div>

    <div class="grid">
      <div class="card">
        <h3>系统迁移 · JSON</h3>
        <p class="muted">
          导出后拿到另一个租户或项目里导入。包内不含表数据和指标，只含规范（主题域 / 分层 / 词根 / 数据等级）。
        </p>
        <a-space wrap>
          <a-button type="primary" @click="exportJson">导出迁移包</a-button>
          <a-button v-if="canWriteSpec" @click="pick('json')">导入迁移包</a-button>
        </a-space>
      </div>
      <div class="card">
        <h3>Excel</h3>
        <p class="muted">
          工作表：主题域、分层、词根、数据等级。查询填「可直接查询 / 登录后可查 / 审批后可查 / 禁止查询」。
        </p>
        <a-space wrap>
          <a-button type="primary" @click="exportXlsx(false)">导出 Excel</a-button>
          <a-button @click="exportXlsx(true)">下载空模板</a-button>
          <a-button v-if="canWriteSpec" @click="pick('xlsx')">导入 Excel</a-button>
        </a-space>
      </div>
    </div>

    <div v-if="preview" class="card mt">
      <div class="preview-h">
        <div>
          <h3>导入预览</h3>
          <p class="muted">
            文件 {{ fileName }}
            <span v-if="preview.pack.source.projectName">
              · 来自 {{ preview.pack.source.tenantName }} / {{ preview.pack.source.projectName }}
            </span>
            · 主题域 {{ preview.pack.domains.length }} · 分层 {{ preview.pack.layers.length }} · 词根
            {{ preview.pack.roots.length }} · 等级 {{ preview.pack.grades.length }}
          </p>
        </div>
        <a-space>
          <template v-if="canWriteSpec">
            <a-checkbox v-model:checked="overwrite">已存在则覆盖</a-checkbox>
            <a-checkbox v-model:checked="replace">先清空本项目规范再写入</a-checkbox>
            <a-button @click="preview = null">取消</a-button>
            <a-button type="primary" @click="confirmImport">导入到当前项目</a-button>
          </template>
          <a-button v-else @click="preview = null">关闭预览</a-button>
        </a-space>
      </div>
      <a-alert
        v-if="preview.warnings.length"
        type="warning"
        show-icon
        class="mb"
        :message="preview.warnings.join('；')"
      />
      <a-tabs>
        <a-tab-pane key="d" :tab="`主题域 ${preview.pack.domains.length}`">
          <a-table
            size="small"
            :pagination="false"
            row-key="code"
            :data-source="preview.pack.domains"
            :columns="dCols"
          />
        </a-tab-pane>
        <a-tab-pane key="l" :tab="`分层 ${preview.pack.layers.length}`">
          <a-table
            size="small"
            :pagination="false"
            row-key="layer"
            :data-source="preview.pack.layers"
            :columns="lCols"
          />
        </a-tab-pane>
        <a-tab-pane key="r" :tab="`词根 ${preview.pack.roots.length}`">
          <a-table
            size="small"
            :pagination="{ pageSize: 8 }"
            row-key="code"
            :data-source="preview.pack.roots"
            :columns="rCols"
          />
        </a-tab-pane>
        <a-tab-pane key="g" :tab="`数据等级 ${preview.pack.grades.length}`">
          <a-table
            size="small"
            :pagination="false"
            row-key="code"
            :data-source="preview.pack.grades"
            :columns="gCols"
          />
        </a-tab-pane>
      </a-tabs>
    </div>

    <input ref="fileRef" type="file" class="hidden" :accept="accept" @change="onFile" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { message, Modal } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import {
  downloadBlob,
  fileStamp,
  parseSpecExcel,
  parseSpecJson,
  specPackToExcelBuffer,
  type SpecPackParseResult,
} from '../../engine/specIo';
import {
  buildCurrentSpecPack,
  canWriteSpec,
  currentProject,
  currentTenant,
  importSpecPack,
  projectDomains,
  projectLayerRules,
  projectGrades,
  projectRoots,
} from '../../stores/app';

const tenant = currentTenant;
const project = currentProject;
const domains = projectDomains;
const layers = projectLayerRules;
const roots = projectRoots;
const grades = projectGrades;
const fileRef = ref<HTMLInputElement>();
const accept = ref('.json,.xlsx,.xls');
const preview = ref<SpecPackParseResult | null>(null);
const fileName = ref('');
const overwrite = ref(true);
const replace = ref(false);

const dCols = [
  { title: '编码', dataIndex: 'code', width: 90 },
  { title: '名称', dataIndex: 'name', width: 120 },
  { title: '定义', dataIndex: 'definition' },
];
const lCols = [
  { title: '层级', dataIndex: 'layer', width: 80 },
  { title: '命名', dataIndex: 'naming' },
  { title: '字段格式', dataIndex: 'fieldFormat' },
  { title: '脱敏', dataIndex: 'masking', width: 90 },
  { title: '空值', dataIndex: 'nullHandling', width: 90 },
];
const rCols = [
  { title: '类型', dataIndex: 'kind', width: 80 },
  { title: '编码', dataIndex: 'code', width: 120 },
  { title: '中文', dataIndex: 'zh', width: 140 },
  { title: '所属域', dataIndex: 'domain', width: 90 },
];
const gCols = [
  { title: '编码', dataIndex: 'code', width: 80 },
  { title: '名称', dataIndex: 'name', width: 100 },
  { title: '查询', dataIndex: 'query', width: 120 },
  { title: '导出', dataIndex: 'export', width: 110 },
  { title: '说明', dataIndex: 'note' },
];

const stamp = computed(() => fileStamp(project.value?.code ?? 'spec'));

function exportJson() {
  const pack = buildCurrentSpecPack();
  downloadBlob(
    `智仓规范-${stamp.value}.json`,
    JSON.stringify(pack, null, 2),
    'application/json;charset=utf-8'
  );
  message.success('已导出 JSON 迁移包');
}

function exportXlsx(template: boolean) {
  const pack = buildCurrentSpecPack();
  const buf = specPackToExcelBuffer(pack, template);
  downloadBlob(
    template ? `智仓规范模板-${stamp.value}.xlsx` : `智仓规范-${stamp.value}.xlsx`,
    buf,
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  );
  message.success(template ? '已下载空模板' : '已导出 Excel');
}

function pick(kind: 'json' | 'xlsx') {
  accept.value = kind === 'json' ? '.json,application/json' : '.xlsx,.xls';
  fileRef.value?.click();
}

async function onFile(ev: Event) {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  fileName.value = file.name;
  try {
    if (/\.json$/i.test(file.name)) {
      preview.value = parseSpecJson(await file.text());
    } else if (/\.xlsx?$/i.test(file.name)) {
      preview.value = parseSpecExcel(await file.arrayBuffer());
    } else {
      message.error('请选择 .json 或 .xlsx 文件');
      return;
    }
    if (preview.value.warnings.length) message.warning(preview.value.warnings[0]);
  } catch (e) {
    preview.value = null;
    message.error(e instanceof Error ? e.message : '文件解析失败');
  }
}

function confirmImport() {
  if (!preview.value) return;
  const pack = preview.value.pack;
  const go = () => {
    importSpecPack(pack, { overwrite: overwrite.value, replace: replace.value });
    preview.value = null;
  };
  if (replace.value) {
    Modal.confirm({
      title: '将清空当前项目的主题域、分层、词根后再写入',
      content: '表、任务、指标不会动。此操作适合空项目或整包迁移。',
      okText: '清空并导入',
      okType: 'danger',
      onOk: go,
    });
    return;
  }
  go();
}
</script>

<style scoped>
h3 {
  margin: 0 0 8px;
  font-size: 15px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.mt {
  margin-top: 12px;
}
.mb {
  margin-bottom: 12px;
}
.preview-h {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}
.hidden {
  display: none;
}
</style>
