<template>
  <div class="editor-wrapper">
    <div class="editor-toolbar vertical">
      <button
        class="toolbar-button toolbar-item"
        @click="toggleWordWrap"
        :title="wordWrap ? '关闭自动换行' : '开启自动换行'"
        :class="{ 'active': wordWrap }"
      >
        <IconWrap :class="{ 'icon-active': wordWrap }" />
      </button>
      <button
        class="toolbar-button toolbar-item"
        @click="toggleSearch"
        title="搜索"
      >
        <IconSearch />
      </button>
      <button
        v-if="props.language === 'sql'"
        class="toolbar-button toolbar-item"
        @click="formatSQL"
        title="格式化 SQL"
      >
        <IconFormat />
      </button>
      <button
        class="toolbar-button toolbar-item"
        @click="clearEditor"
        title="清空内容"
      >
        <IconClear />
      </button>
      <button
        class="toolbar-button toolbar-item"
        @click="copyContent"
        title="复制全部"
      >
        <IconCopy />
      </button>
      <button
        class="toolbar-button toolbar-item"
        @click="undoChange"
        title="撤销"
      >
        <IconUndo />
      </button>
      <button
        class="toolbar-button toolbar-item"
        @click="redoChange"
        title="重做"
      >
        <IconRedo />
      </button>
      <transition name="fade">
        <div v-if="showCopyToast" class="copy-toast">
          <IconCopy class="copy-toast-icon" />
          <span>复制成功</span>
        </div>
      </transition>
    </div>
    <div
      ref="editorContainer"
      class="monaco-editor-container"
    />
  </div>
</template>

<script lang="ts" setup>
import { onMounted, onUnmounted, ref, watch } from 'vue';
import IconWrap from '../Icon/IconWrap.vue';
import IconFormat from '../Icon/IconFormat.vue';
import IconClear from '../Icon/IconClear.vue';
import IconCopy from '../Icon/IconCopy.vue';
import IconUndo from '../Icon/IconUndo.vue';
import IconRedo from '../Icon/IconRedo.vue';
import IconSearch from '../Icon/IconSearch.vue';
import { format } from 'sql-formatter';
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import CssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker';
import HtmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker';
import TsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker';

// 配置 Monaco Editor 的 worker
window.MonacoEnvironment = {
  getWorker(_: any, label: string) {
    if (label === 'json') return new JsonWorker();
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker();
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker();
    if (label === 'typescript' || label === 'javascript') return new TsWorker();
    return new EditorWorker();
  },
};

// Props 定义
interface Props {
  width?: string;
  height?: string;
  modelValue?: string;
  language?: string;
  theme?: string;
}

const props = withDefaults(defineProps<Props>(), {
  width: '100%',
  height: '100%',
  modelValue: '',
  language: 'sql',
  theme: 'vs'
});

const emit = defineEmits(['update:modelValue']);

const editorContainer = ref<HTMLElement | null>(null);
let editor: monaco.editor.IStandaloneCodeEditor | null = null;
const wordWrap = ref(true); // 默认开启自动换行
const showCopyToast = ref(false); // 控制复制成功提示的显示
const isSearchVisible = ref(false); // 控制搜索框的显示

// 切换搜索框显示
const toggleSearch = () => {
  isSearchVisible.value = !isSearchVisible.value;
  if (editor) {
    if (isSearchVisible.value) {
      // 显示搜索框
      editor.trigger('', 'actions.find', null);
    } else {
      // 隐藏搜索框
      editor.trigger('', 'closeFindWidget', null);
    }
  }
};

// 切换自动换行功能
// SQL格式化功能
const formatSQL = () => {
  if (editor && props.language === 'sql') {
    try {
      const currentValue = editor.getValue();
      const formattedSQL = format(currentValue, {
        language: 'sql',
        keywordCase: 'upper', // SQL关键字大写
        indentStyle: 'standard',
        linesBetweenQueries: 2 // 查询之间保留2行空行
      });
      editor.setValue(formattedSQL);
      emit('update:modelValue', formattedSQL);
    } catch (error) {
      console.error('SQL格式化失败:', error);
    }
  }
};

// 清空编辑器内容
const clearEditor = () => {
  if (editor) {
    editor.setValue('');
    emit('update:modelValue', '');
  }
};

// 复制编辑器内容到剪贴板
const copyContent = () => {
  if (editor) {
    const content = editor.getValue();
    if (content) {
      navigator.clipboard.writeText(content)
        .then(() => {
          showCopyToast.value = true;
          setTimeout(() => {
            showCopyToast.value = false;
          }, 2000); // 2秒后隐藏提示
        })
        .catch(err => {
          console.error('复制失败:', err);
        });
    }
  }
};

// 撤销操作
const undoChange = () => {
  if (editor) {
    editor.trigger('keyboard', 'undo', null);
  }
};

// 重做操作
const redoChange = () => {
  if (editor) {
    editor.trigger('keyboard', 'redo', null);
  }
};

const toggleWordWrap = () => {
  wordWrap.value = !wordWrap.value;
  if (editor) {
    editor.updateOptions({
      wordWrap: wordWrap.value ? 'on' : 'off'
    });
  }
};

// 定义护眼色主题
const defineEyecareTheme = () => {
  monaco.editor.defineTheme('vs-eyecare', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: '2C4C35', fontStyle: 'bold' },
      { token: 'string', foreground: '2C4C35' },
      { token: 'identifier', foreground: '2C4C35' }
    ],
    colors: {
      'editor.background': '#C7EDCC',
      'editor.foreground': '#2C4C35',
      'editor.lineHighlightBackground': '#B8E6C0',
      'editorLineNumber.foreground': '#2C4C35',
      'editor.selectionBackground': '#A8D5B0',
      'editorCursor.foreground': '#2C4C35',
    }
  });
};

// 创建编辑器实例
const createEditor = () => {
  if (!editorContainer.value) return;

  // 定义护眼色主题
  defineEyecareTheme();

  // 创建编辑器
  editor = monaco.editor.create(editorContainer.value, {
    value: props.modelValue,
    language: props.language,
    theme: props.theme,
    automaticLayout: true,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    lineNumbers: 'on',
    roundedSelection: false,
    readOnly: false,
    cursorStyle: 'line',
    selectOnLineNumbers: true,
    contextmenu: true,
    wordWrap: 'on', // 默认开启自动换行
    scrollbar: {
      horizontal: 'auto',
      useShadows: true,
      horizontalScrollbarSize: 10
    }
  });

  // 监听内容变化
  editor.onDidChangeModelContent(() => {
    const value = editor?.getValue() || '';
    emit('update:modelValue', value);
  });
};

// 组件挂载时创建编辑器
onMounted(() => {
  createEditor();
});

// 组件卸载时销毁编辑器
onUnmounted(() => {
  if (editor) {
    editor.dispose();
    editor = null;
  }
});

// 暴露方法给父组件
defineExpose({
  getEditor: () => editor,
  toggleSearch
});

// 监听主题变化
watch(() => props.theme, (newTheme) => {
  if (newTheme && editor) {
    monaco.editor.setTheme(newTheme);
  }
});

// 监听语言变化
watch(() => props.language, (newLang) => {
  if (editor && newLang) {
    const model = editor.getModel();
    if (model) {
      monaco.editor.setModelLanguage(model, newLang);
    }
  }
});

// 监听 modelValue 变化
watch(() => props.modelValue, (newValue) => {
  if (editor && newValue !== editor.getValue()) {
    editor.setValue(newValue);
  }
});
</script>

<style scoped>
.editor-wrapper {
  display: flex;
  flex-direction: row; /* 改为水平布局 */
  height: 100%;
  width: 100%;
}

.editor-toolbar {
  display: flex;
  padding: 4px;
  background-color: #f5f5f5;
  border: 1px solid #ddd;
  border-right: none;
}

.editor-toolbar.vertical {
  flex-direction: column;
  width: 40px; /* 设置工具栏宽度 */
  align-items: center; /* 水平居中按钮 */
  padding-top: 8px; /* 顶部增加一些内边距 */
  padding-bottom: 8px; /* 底部增加一些内边距 */
}

.toolbar-button {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  color: #666;
  background-color: white;
  border: 1px solid #ddd;
  border-radius: 3px;
  cursor: pointer;
  transition: all 0.2s;
  width: 28px;
  height: 28px;
}

.toolbar-button:hover {
  background-color: #e6e6e6;
  border-color: #adadad;
}

.toolbar-button.active {
  background-color: #e6e6e6;
  border-color: #adadad;
  color: #1890ff;
}

.icon-active {
  color: #1890ff;
}

.toolbar-item {
  margin-bottom: 8px; /* 统一设置下边距 */
}

.toolbar-item:last-child {
  margin-bottom: 0; /* 最后一个按钮不需要下边距 */
}

/* 移除旧的ml-2类 */

.monaco-editor-container {
  flex: 1;
  width: calc(100% - 40px); /* 减去工具栏的宽度 */
  height: 100%;
  border: 1px solid #ddd;
}

.copy-toast {
  display: inline-flex;
  align-items: center;
  margin-top: 8px;
  padding: 4px 12px;
  background-color: #f6ffed;
  border: 1px solid #b7eb8f;
  border-radius: 4px;
  color: #52c41a;
  font-size: 14px;
  line-height: 1.5;
}

.copy-toast-icon {
  margin-right: 6px;
  width: 14px;
  height: 14px;
}

/* 淡入淡出动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>