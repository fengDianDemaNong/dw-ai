<template>
  <ConfigProvider :wave="{ disabled: true }">
    <div class="analyze-page" :class="THEME_CLASS[preferences.theme]">
      <Header
        :model="model"
        :layout="layout"
        v-model:columnName="columnName"
        v-model:isCreateTable="isCreateTable"
        v-model:includeTemp="includeTemp"
        v-model:selectedMatelake="selectedMatelake"
        v-model:selectedCatalog="selectedCatalog"
        @layoutChange="(newLayout: string) => (layout = newLayout)"
        @nodeSizeChange="(newTestSize: number) => (testSize = newTestSize)"
        @handleParseSql="handleParseSql"
        @modelChange="handleChangeModel"
        @dbTypeChange="(newDbType: string) => (selectedDbType = newDbType)"
        @parseModeChange="(newMode: string) => (parseMode = newMode)"
        @sourceChange="handleSourceChange"
        @handleSaveLineage="handleSaveLineage"
        :saving="saving"
      />
      <main class="flex-auto overflow-hidden border-t border-gray-200">
        <div class="splitPane">
          <div class="pane1" :style="{ width: isEditorCollapsed ? '40px' : `${editorWidth}px` }" ref="pane1">
            <!-- 添加收起/展开按钮 -->
            <div class="collapse-btn" @click="toggleEditorCollapse">
              <div class="collapse-icon">{{ isEditorCollapsed ? '›' : '‹' }}</div>
            </div>

            <div
  v-show="!isEditorCollapsed"
  class="flex flex-auto h-full"
  ref="ref1"
>
  <MonacoEditor
    ref="monacoEditorRef"
    :width="`${editorWidth - 10}px`"
    :height="'100%'"
    :language="'sql'"
    :theme="preferences.theme"
    v-model="code"
  />
</div>

            <!-- 添加拖拽条 -->
            <div
              v-show="!isEditorCollapsed"
              class="resize-handle"
              @mousedown="startResize"
            ></div>
          </div>
          <div class="pane2">
            <div
              class="relative w-full overflow-auto md:h-full"
              ref="ref2"
            >
              <Spin :spinning="loading">
                <!-- 解析诊断：让用户知道图为什么不完整，而不是面对空图无从排查 -->
                <div v-if="hasDiagnostics" class="diagnostics-bar">
                  <div class="diagnostics-summary" @click="showDiagnostics = !showDiagnostics">
                    <span class="diagnostics-icon">!</span>
                    <span>{{ diagnosticsSummary }}</span>
                    <span class="diagnostics-toggle">{{ showDiagnostics ? '收起' : '查看详情' }}</span>
                  </div>
                  <div v-show="showDiagnostics" class="diagnostics-detail">
                    <div v-if="failedStatements.length" class="diagnostics-section">
                      <div class="diagnostics-title">解析失败的语句</div>
                      <div v-for="f in failedStatements" :key="f.index" class="diagnostics-item">
                        <div class="diagnostics-item-head">第 {{ f.index }} 条：{{ f.message }}</div>
                        <pre class="diagnostics-sql">{{ f.sql }}</pre>
                      </div>
                    </div>
                    <div v-if="unresolvedTables.length" class="diagnostics-section">
                      <div class="diagnostics-title">结构未知的表（select * 等场景会缺列）</div>
                      <div class="diagnostics-item">{{ unresolvedTables.join('、') }}</div>
                    </div>
                    <div v-if="parseWarnings.length" class="diagnostics-section">
                      <div class="diagnostics-title">告警</div>
                      <div v-for="(w, i) in parseWarnings" :key="i" class="diagnostics-item">{{ w }}</div>
                    </div>
                  </div>
                </div>
                <template v-if="model === 'test'">
                  <LineageGraphTest
                    :layout="layout"
                    :lineageData="lineageData"
                    :highlightColor="highlightColor"
                    :textWaterMarker="preferences.watermark"
                  />
                </template>
                <template v-else>
                  <LineageGraph
                    :layout="layout"
                    :lineageData="lineageData"
                    v-model:nodeSize="nodeSize"
                    v-model:nodeLevel="nodeLevel"
                    @nodeLevel="(newNodeLevel: number) => (nodeLevel = newNodeLevel)"
                    :highlightColor="highlightColor"
                    :textWaterMarker="preferences.watermark"
                    :isEditorCollapsed="isEditorCollapsed"
                    :canvasWidth="canvasWidth"
                    :canvasHeight="uiState.contentHeight"
                    :showMinimap="preferences.showMinimap"
                    @selectNode="handleSelectNode"
                  />
                </template>
              </Spin>

              <!-- 节点/层数以前在页面底部占一整条 Footer。它只是两个数字，
                   放画布角上就够，省下的一条给画布 -->
              <div v-if="nodeSize" class="graph-stat">
                节点 {{ nodeSize }} · 层数 {{ nodeLevel }}
              </div>
            </div>
          </div>
          <!-- </SplitPane> -->
        </div>
      </main>
      <!-- <ITour
        ref1="ref1"
        ref2="ref2"
      /> -->
      <div v-if="matches.length > 0" class="match-navigation">
  <div class="match-header">
    <span>匹配 {{ currentMatchIndex + 1 }} / {{ matches.length }}</span>
    <Button @click="closeMatchNavigation" size="small" class="close-btn">
      <span class="close-icon">×</span>
    </Button>
  </div>
  <div class="match-buttons">
    <Button @click="prevMatch" size="small">
      <span class="match-nav-icon">←</span> 上一个
    </Button>
    <Button @click="nextMatch" size="small">
      下一个 <span class="match-nav-icon">→</span>
    </Button>
  </div>
  <div class="match-shortcuts">
    快捷键: Alt+P (上一个) / Alt+N (下一个)
  </div>
</div>
    </div>
  </ConfigProvider>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { Switch, Button } from 'ant-design-vue';
import * as monaco from 'monaco-editor';
import Header from '../../components/Header/index.vue';
import sourceData from '../../test/data.json';
import MonacoEditor from '../../components/MonacoEditor/index.vue';
import LineageGraph from '../../components/LineageGraph/index.vue';
import LineageGraphTest from '../../components/LineageGraphTest/index.vue';
import { getLineageData, saveLineage } from '../../services/api';
import { uiState } from '../../stores/ui';
import { preferences, THEME_CLASS } from '../../stores/preferences';
// import SplitPane from 'vue-splitpane';
import { message, Spin, ConfigProvider } from 'ant-design-vue';
import ITour from '../../components/Tour/index.vue';
import { sql } from '../../test/sql';
import { initData } from '../../test/test';

const props = defineProps();
const columnName = ref(''); // 添加字段名输入框的值
const size = ref({
  percentage: 0.25,
  current: 340,
  min: 340,
  max: 0
});
const layout = ref('vertical');
const model = ref(import.meta.env.VITE_ENV);
const testSize = ref(0);
const selectedDbType = ref('');
const parseMode = ref('createTable'); // 默认为解析建表语句
const selectedMatelake = ref('');
const selectedCatalog = ref('');
/** 选中的元数据服务配置，来自 Header 的「元数据来源」下拉；null 表示不接外部元数据 */
const selectedSourceId = ref<number | null>(null);
const selectedSourceType = ref<string | null>(null);

const handleSourceChange = ({ sourceId, type }: { sourceId?: number; type?: string }) => {
  selectedSourceId.value = sourceId ?? null;
  selectedSourceType.value = type ?? null;
};
// 保存默认SQL作为初始值
const defaultSql = sql();
const code = ref(defaultSql);
const monacoEditorRef = ref<InstanceType<typeof MonacoEditor> | null>(null);
const loading = ref(false);
const lineageData = ref<any>({});
// 解析诊断信息
const parseWarnings = ref<string[]>([]);
const failedStatements = ref<any[]>([]);
const unresolvedTables = ref<string[]>([]);
const showDiagnostics = ref(false);

const hasDiagnostics = computed(
  () =>
    failedStatements.value.length > 0 ||
    unresolvedTables.value.length > 0 ||
    parseWarnings.value.length > 0
);

const diagnosticsSummary = computed(() => {
  const parts: string[] = [];
  if (failedStatements.value.length) {
    parts.push(`${failedStatements.value.length} 条语句解析失败`);
  }
  if (unresolvedTables.value.length) {
    parts.push(`${unresolvedTables.value.length} 张表结构未知`);
  }
  if (!parts.length && parseWarnings.value.length) {
    parts.push(parseWarnings.value[0]);
  }
  return parts.join('，');
});
// 高亮色不在设置里暴露：它只在「点字段看上下游」时用一下，没人需要改
const highlightColor = ref('red');
const isCreateTable = ref(false); // 控制是否为创建表操作
/**
 * 血缘图上是否保留临时表。默认 false —— 所见即所存。
 *
 * 保存接口一律过滤临时表，与这个开关无关：开关是展示偏好，
 * 「库里不存临时表」是数据约束，混同的话勾一下就能把临时表灌进库里。
 */
const includeTemp = ref(false);
const ref1 = ref(null);
const ref2 = ref(null);
const nodeSize = ref(0);
const nodeLevel = ref(0);
// 添加画布宽度计算
const canvasWidth = ref(0);

// 添加匹配相关的变量
const matches = ref<{ lineNumber: number; startColumn: number; endColumn: number; text: string }[]>([]);
const currentMatchIndex = ref(0);

// 编辑器宽度和折叠状态
const editorWidth = ref(340);
const isEditorCollapsed = ref(false);
const minEditorWidth = 200;
const maxEditorWidth = ref(Math.floor(document.documentElement.clientWidth * 0.8)); // 设置为页面宽度的80%

const splitPaneProps = {
  split: 'vertical',
  minSize: size.value.min,
  maxSize: size.value.max,
  size: size.value.current,
  // onChange: (newSize: any) => console.log(newSize),
  // onDragStarted: () => console.log('拖动开始'),
  // onDragFinished: () => console.log('拖动结束'),
  // allowResize: false,
};

const handleParseSql = () => {
  // 更新code值为当前输入框的内容（不需要额外操作，因为v-model已经自动同步）
  if (model.value === 'test') {
    nodeSize.value = testSize.value;
    nodeLevel.value = testSize.value;

    lineageData.value = initData(testSize.value);
    console.log(
      '🚀 ~ file: index.vue:123 ~ handleParseSql ~ lineageData.value:',
      lineageData.value
    );
    return;
  }

  // 检查输入
  if (!code.value) {
    message.error('请输入SQL语句');
    return;
  }

  const useExternalSource = selectedSourceId.value !== null;

  // 检查数据库类型（不接外部元数据时必须选）
  if (!useExternalSource && !selectedDbType.value) {
    message.error('请选择数据库类型');
    return;
  }

  // Gravitino 需要两级定位；dbx 的目标连接写在配置里，这里不用选
  if (useExternalSource && selectedSourceType.value === 'GRAVITINO'
      && (!selectedMatelake.value || !selectedCatalog.value)) {
    message.error('请选择 Matelake 和 Catalog');
    return;
  }

  loading.value = true;
  parseWarnings.value = [];
  failedStatements.value = [];
  unresolvedTables.value = [];

  // 统一走 /api/lineage/analyze 并带上 sourceId：
  // 后端会把选中的元数据服务插到「DDL > 外部来源 > 结构推断」这条链的中间。
  // Gravitino 专用的 /api/mate/lineage/analyze 仍然保留，供旧调用方使用。
  const apiPromise = getLineageData(
    selectedDbType.value || 'hive',
    code.value,
    columnName.value || undefined,
    // 用页面上那个「使用建表语句获取表信息」开关的实际状态。
    // 原先这里写的是 parseMode === 'createTable'，而 parseMode 的取值里根本没有
    // 'createTable' 这个值，导致开关打了也不生效。
    !useExternalSource && isCreateTable.value,
    useExternalSource
      ? {
          sourceId: selectedSourceId.value!,
          metalake: selectedMatelake.value || undefined,
          catalog: selectedCatalog.value || undefined,
        }
      : undefined,
    includeTemp.value
  );

  apiPromise
    .then((response: any) => {
      if (response && (response.code === 0 || response.code === 200)) {
        lineageData.value = response.data || {};
        parseWarnings.value = response.warnings || [];
        failedStatements.value = response.failedStatements || [];
        unresolvedTables.value = response.unresolvedTables || [];
        // 有失败语句时默认展开，避免用户忽略
        showDiagnostics.value = failedStatements.value.length > 0;

        if (lineageData.value.withProcessData && lineageData.value.withProcessData.size) {
          nodeSize.value = lineageData.value.withProcessData.size;
          nodeLevel.value = lineageData.value.withProcessData.level || 0;
        }
      } else {
        throw new Error(response?.message || '未知错误');
      }
    })
    .catch((e: any) => {
      console.error('API 错误:', e);
      message.error('处理异常！' + (e.message || e));
    })
    .finally(() => {
      loading.value = false;
    });
  // 更新大小
  updateSize();
}

/**
 * 把当前 SQL 的血缘存进数据库。
 *
 * 只把 SQL 发给后端重新解析一次，而不是把前端已有的图传过去 ——
 * 那张图经过了列过滤（字段名输入框）等展示层处理，存进去会是残缺的血缘。
 */
const saving = ref(false);

const handleSaveLineage = async () => {
  if (model.value === 'test') {
    message.warning('测试模式下的示例数据不保存');
    return;
  }
  if (!code.value) {
    message.error('请输入SQL语句');
    return;
  }
  const useExternalSource = selectedSourceId.value !== null;
  if (!useExternalSource && !selectedDbType.value) {
    message.error('请选择数据库类型');
    return;
  }

  saving.value = true;
  try {
    const result = await saveLineage({
      dbType: selectedDbType.value || 'hive',
      querySql: code.value,
      isCreateTable: !useExternalSource && isCreateTable.value,
      sourceId: selectedSourceId.value ?? undefined,
      metalake: selectedMatelake.value || undefined,
      catalog: selectedCatalog.value || undefined,
    });
    const tables = result.versions.map((v) => `${v.targetTable} v${v.versionNo}`).join('、');
    message.success(`已保存 ${result.edges} 条血缘：${tables}。可在「数据地图 › 血缘」页的血缘图里查看`);
  } catch (e: any) {
    message.error('保存失败：' + (e?.message || e));
  } finally {
    saving.value = false;
  }
};

const updateSize = () => {
  const isPreview = layout.value === 'preview';
  size.value.current = isPreview ? 0 : editorWidth.value;
  size.value.min = isPreview ? 0 : minEditorWidth;
  // 使用动态计算的最大宽度
  size.value.max = isPreview ? 0 : maxEditorWidth.value;
};

// 处理编辑器折叠/展开
const toggleEditorCollapse = () => {
  isEditorCollapsed.value = !isEditorCollapsed.value;
  // 如果是从折叠状态展开，恢复之前的宽度
  if (!isEditorCollapsed.value && editorWidth.value < minEditorWidth) {
    editorWidth.value = 340;
  }
  // 更新画布宽度
  calculateCanvasWidth();
};

// 计算画布宽度
const calculateCanvasWidth = () => {
  // 用内容区宽度而不是整屏宽度：左边还有侧边栏，按整屏算画布会横向溢出。
  // 侧边栏展开/收起时 AppLayout 的 ResizeObserver 会更新它，下面的 watch 跟着重算
  const areaWidth = uiState.contentWidth;
  // 根据编辑器是否折叠计算画布宽度
  canvasWidth.value = areaWidth - (isEditorCollapsed.value ? 40 : editorWidth.value);
};

watch(() => uiState.contentWidth, calculateCanvasWidth);

// 处理拖拽调整宽度
const pane1 = ref<HTMLElement | null>(null);
const startResize = (e: MouseEvent) => {
  e.preventDefault();

  const startX = e.clientX;
  const startWidth = pane1.value?.offsetWidth ?? 0;

  const handleMouseMove = (moveEvent: MouseEvent) => {
    moveEvent.preventDefault();
    const deltaX = moveEvent.clientX - startX;
    // 使用 maxEditorWidth.value 来获取响应式的最大宽度值
    const newWidth = Math.max(minEditorWidth, Math.min(maxEditorWidth.value, startWidth + deltaX));
    editorWidth.value = newWidth;
    if (pane1.value) {
      pane1.value.style.width = `${newWidth}px`;
    }
    // 更新画布宽度
    calculateCanvasWidth();
  };

  const handleMouseUp = () => {
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', handleMouseUp);
  };

  document.addEventListener('mousemove', handleMouseMove, { passive: false });
  document.addEventListener('mouseup', handleMouseUp, { passive: true });
};

onMounted(() => {
  // 初始化时设置最大宽度为页面宽度的80%
  maxEditorWidth.value = Math.floor(document.documentElement.clientWidth * 0.8);
  updateSize();
  // 初始化画布宽度
  calculateCanvasWidth();

  window.addEventListener('resize', () => {
    // 更新编辑器最大宽度
    maxEditorWidth.value = Math.floor(document.documentElement.clientWidth * 0.8);
    updateSize();
    // 更新画布宽度
    calculateCanvasWidth();
  });
});

onUnmounted(() => {
  window.removeEventListener('resize', updateSize);
});

const handleChangeModel = (newModel: string) => {
  model.value = newModel;
  lineageData.value = null;
  nodeSize.value = 0;
  nodeLevel.value = 0;
  testSize.value = 0;
};

const handleSelectNode = ({ tableName, fieldName }: { tableName?: string; fieldName?: string }) => {
  let bestMatchLineIndex = -1;
  let bestMatchColumnIndex = -1;
  let fieldLineIndex = -1;
  let fieldColumnIndex = -1;
  let tableLineIndex = -1;
  let tableColumnIndex = -1;

  if (!tableName && !fieldName) return;

  // 在SQL中查找表名和字段名
  const searchText = fieldName || tableName;
  if (!searchText) return;
  const fieldRegex = fieldName ? new RegExp(`\\b${escapeRegExp(fieldName)}\\b`, 'gi') : null;
  const tableRegex = tableName ? new RegExp(`\\b${escapeRegExp(tableName)}\\b`, 'gi') : null;

  const regex = new RegExp(`\\b${escapeRegExp(searchText)}\\b`, 'gi');

  const lines = code.value.split('\n');
  matches.value = [];

  // 首先查找精确匹配
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let match;
    while ((match = regex.exec(line)) !== null) {
      // 确保匹配的是完整的单词
      const prevChar = line[match.index - 1] || ' ';
      const nextChar = line[match.index + searchText.length] || ' ';
      const isWordBoundary = /\W/.test(prevChar) && /\W/.test(nextChar);

      if (isWordBoundary) {
        matches.value.push({
          lineNumber: i + 1,
          startColumn: match.index + 1,
          endColumn: match.index + searchText.length + 1,
          text: searchText
        });
      }
    }
  }

  // 如果没有找到匹配，尝试不区分大小写的搜索
  if (matches.value.length === 0) {
    const caseInsensitiveRegex = new RegExp(escapeRegExp(searchText), 'gi');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      let match;
      while ((match = caseInsensitiveRegex.exec(line)) !== null) {
        matches.value.push({
          lineNumber: i + 1,
          startColumn: match.index + 1,
          endColumn: match.index + match[0].length + 1,
          text: match[0]
        });
      }
    }
  }

  if (matches.value.length > 0) {
    // message.info(`找到 ${matches.value.length} 个匹配项`);
    currentMatchIndex.value = 0;
    highlightCurrentMatch();

    // 获取第一个匹配项的位置
    const firstMatch = matches.value[0];
    bestMatchLineIndex = firstMatch.lineNumber - 1;
    bestMatchColumnIndex = firstMatch.startColumn - 1;

    // 如果找到了匹配位置，就定位到相应位置
    nextTick(() => {
      if (monacoEditorRef.value) {
        const editor = monacoEditorRef.value.getEditor();
        if (!editor) return;
        if (bestMatchLineIndex !== -1) {
          // 展开编辑器（如果已折叠）
          if (isEditorCollapsed.value) {
            toggleEditorCollapse();
          }

          // 定位到匹配位置并高亮
          editor.revealPositionInCenter({
            lineNumber: firstMatch.lineNumber,
            column: firstMatch.startColumn
          });

          // 设置光标位置
          editor.setPosition({
            lineNumber: firstMatch.lineNumber,
            column: firstMatch.startColumn
          });

          // 选中匹配的文本
          editor.setSelection({
            startLineNumber: firstMatch.lineNumber,
            startColumn: firstMatch.startColumn,
            endLineNumber: firstMatch.lineNumber,
            endColumn: firstMatch.endColumn
          });

          editor.focus();
        }
      }
    });
  } else {
    message.warning(`未找到 "${searchText}" 的匹配项`);
  }
};

// 高亮当前匹配项
const highlightCurrentMatch = () => {
  if (matches.value.length === 0) return;

  nextTick(() => {
    if (monacoEditorRef.value) {
      const editor = monacoEditorRef.value.getEditor();
      if (!editor) return;
      const match = matches.value[currentMatchIndex.value];

      // 展开编辑器（如果已折叠）
      if (isEditorCollapsed.value) {
        toggleEditorCollapse();
      }

      // 定位到匹配位置
      editor.revealPositionInCenter({
        lineNumber: match.lineNumber,
        column: match.startColumn
      });

      // 选中匹配的文本
      editor.setSelection({
        startLineNumber: match.lineNumber,
        startColumn: match.startColumn,
        endLineNumber: match.lineNumber,
        endColumn: match.endColumn
      });

      editor.focus();
    }
  });
};

// 导航到下一个匹配项
const nextMatch = () => {
  if (matches.value.length === 0) return;
  currentMatchIndex.value = (currentMatchIndex.value + 1) % matches.value.length;
  highlightCurrentMatch();
};

// 导航到上一个匹配项
const prevMatch = () => {
  if (matches.value.length === 0) return;
  currentMatchIndex.value = (currentMatchIndex.value - 1 + matches.value.length) % matches.value.length;
  highlightCurrentMatch();
};

// 关闭匹配导航
const closeMatchNavigation = () => {
  matches.value = [];
  currentMatchIndex.value = 0;
  // 清除编辑器中的高亮
  if (monacoEditorRef.value) {
    const editor = monacoEditorRef.value.getEditor();
    if (!editor) return;
    editor.setSelection({
      startLineNumber: 1,
      startColumn: 1,
      endLineNumber: 1,
      endColumn: 1
    });
  }
};

// 添加键盘快捷键
onMounted(() => {
  // 初始化时设置最大宽度为页面宽度的80%
  maxEditorWidth.value = Math.floor(document.documentElement.clientWidth * 0.8);
  updateSize();
  // 初始化画布宽度
  calculateCanvasWidth();

  window.addEventListener('resize', () => {
    // 更新编辑器最大宽度
    maxEditorWidth.value = Math.floor(document.documentElement.clientWidth * 0.8);
    updateSize();
    // 更新画布宽度
    calculateCanvasWidth();
  });

  // 添加键盘事件监听器
  window.addEventListener('keydown', (e) => {
    if (e.altKey && e.key === 'n') {
      e.preventDefault();
      nextMatch();
    } else if (e.altKey && e.key === 'p') {
      e.preventDefault();
      prevMatch();
    }
  });
});

onUnmounted(() => {
  window.removeEventListener('resize', updateSize);
  window.removeEventListener('keydown', (e) => {
    if ((e.altKey && e.key === 'n') || (e.altKey && e.key === 'p')) {
      e.preventDefault();
    }
  });
});

// 辅助函数：转义正则表达式特殊字符
function escapeRegExp(string: string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
</script>

<style scoped>
.analyze-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.analyze-page > main {
  flex: 1;
  min-height: 0;
  position: relative;
}

/* 节点/层数浮层，替代原来的底部 Footer */
.graph-stat {
  position: absolute;
  left: 12px;
  bottom: 12px;
  z-index: 50;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  color: #4b5563;
  pointer-events: none;
}

.splitPane {
  display: flex;
  flex: 1 1 0%;
  height: 100%;
  position: absolute;
  outline: none;
  overflow: hidden;
  user-select: text;
  flex-direction: row;
  left: 0px;
  right: 0px;
  transition: background-color 0.3s;
}

.pane1 {
  flex: 0 0 auto;
  position: relative;
  outline: none;
  height: 100%;
  overflow: hidden;
  transition: width 0.3s ease;
  display: flex;
  align-items: stretch;
}

.pane2 {
  flex: 1 1 0%;
  position: relative;
  outline: none;
}

/* 拖拽条样式 */
.resize-handle {
  width: 8px;
  height: 100%;
  cursor: col-resize;
  background-color: #f0f0f0;
  transition: background-color 0.2s;
  z-index: 100;
  position: absolute;
  right: -4px;
  top: 0;
}

.resize-handle:hover {
  background-color: #ddd;
}

/* 收起/展开按钮样式 */
.collapse-btn {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 20px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f0f0f0;
  cursor: pointer;
  z-index: 11;
  border-radius: 4px 0 0 4px;
  transition: background-color 0.2s;
}

.collapse-btn:hover {
  background-color: #ddd;
}

.collapse-icon {
  font-size: 18px;
  font-weight: bold;
}

/* 匹配导航样式 */
.match-navigation {
  position: fixed;
  bottom: 80px;  /* 调整位置，避免与其他元素重叠 */
  right: 20px;
  background-color: var(--ant-primary-1);
  padding: 12px;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  font-size: 14px;
  border: 1px solid var(--ant-primary-3);
  min-width: 240px;
}

.match-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  margin-bottom: 8px;
}

.match-navigation span {
  color: var(--ant-text-color);
  font-weight: 500;
}

.close-btn {
  padding: 0 6px;
  height: 24px;
  min-width: 24px;
  font-size: 14px;
  line-height: 24px;
}

.close-icon {
  font-weight: bold;
}

.match-buttons {
  display: flex;
  gap: 8px;
  width: 100%;
}

.match-buttons .ant-btn {
  flex: 1;
  height: 32px;
  padding: 0 12px;
  font-size: 14px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.match-nav-icon {
  font-size: 16px;
  line-height: 1;
}

.match-shortcuts {
  font-size: 12px;
  color: var(--ant-text-color-secondary);
  margin-top: 4px;
}

.theme-dark .match-shortcuts {
  color: rgba(255, 255, 255, 0.45);
}

.theme-eyecare .match-shortcuts {
  color: rgba(0, 0, 0, 0.45);
}

.theme-dark .match-navigation {
  background-color: rgba(45, 45, 45, 0.95);
  border-color: rgba(255, 255, 255, 0.15);
}

.theme-dark .match-navigation span {
  color: rgba(255, 255, 255, 0.85);
}

.theme-eyecare .match-navigation {
  background-color: rgba(242, 234, 212, 0.95);
  border-color: rgba(0, 0, 0, 0.1);
}

.theme-eyecare .match-navigation span {
  color: rgba(0, 0, 0, 0.85);
}

/* 确保两个按钮样式一致 */
.match-buttons .ant-btn {
  background-color: #ffffff;
  border-color: #d9d9d9;
  color: rgba(0, 0, 0, 0.85);
}

.theme-dark .match-buttons .ant-btn {
  background-color: #1f1f1f;
  border-color: #434343;
  color: rgba(255, 255, 255, 0.85);
}

.theme-eyecare .match-buttons .ant-btn {
  background-color: #f2ead4;
  border-color: #d3c17a;
  color: rgba(0, 0, 0, 0.85);
}

/* 按钮悬停效果 */
.match-buttons .ant-btn:hover {
  background-color: #f0f0f0;
  border-color: #40a9ff;
  color: #40a9ff;
}

.theme-dark .match-buttons .ant-btn:hover {
  background-color: #2a2a2a;
  border-color: #177ddc;
  color: #177ddc;
}

.theme-eyecare .match-buttons .ant-btn:hover {
  background-color: #e8e0c5;
  border-color: #52c41a;
}
</style>
