<template>
  <div>
    <div
        ref="canvasWrapper"
        class="canvas-wrapper"
    >
      <div class="g6-component-topbar">
        <Topbar
            :ref="topBarRef"
            :fieldChecked="fieldCheckedRef"
            :wholeChecked="wholeCheckedRef"
            :level="currentLevel"
            :maxLevel="maxLevel"
            @handleFieldLineage="onFieldLineage"
            @handleWholeLineage="onWholeLineage"
            @levelChange="onLevelChange"
        />
      </div>
      <div
          ref="toolbarRef"
          class="g6-component-toolbar"
      >
        <Toolbar
            :layout="layout"
            :handleChangeSize="handleChangeSize"
            @handleZoomOut="handleZoomIn(graphRef)"
            @handleZoomIn="handleZoomOut(graphRef)"
            @handleRealZoom="handleRealZoom(graphRef)"
            @handleAutoZoom="handleAutoZoom(graphRef)"
            @handleRefreshLayout="handleRefreshLayout(graphRef)"
            @handleDownloadImage="handleDownloadImage(graphRef)"
            @handleEnterFullscreen="handleEnterFullscreen(toRaw(canvasWrapper))"
            @handleExitFullscreen="handleExitFullscreen"
        />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted, onUnmounted, reactive, watch, watchEffect, toRaw } from 'vue';
import Toolbar from './components/Toolbar/index.vue';
import Topbar from './components/Topbar/index.vue';
import G6 from '@antv/g6';
import './index.css';
import './registerShape';
import './registerLayout';
import {
  collapseData,
  getLeftRelation,
  getRightRelation,
  transformData,
} from '../../utils/common';
import {
  clearAllStats,
  handleAutoZoom,
  handleDownloadImage,
  handleEnterFullscreen,
  handleExitFullscreen,
  handleHighlightColor,
  handleRealZoom,
  handleRefreshLayout,
  handleTextWaterMarker,
  handleZoomIn,
  handleZoomOut,
  renderGraph,
  setLeftStats,
  setRightStats,
} from '../../utils/graphUtil';
import { dataTransform, initData } from '../../test/test';
import screenfull from "screenfull";

// 防抖函数
const debounce = (fn: Function, delay: number) => {
  let timer: any = null;
  return function (...args: any[]) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      fn.apply(null, args);
    }, delay);
  };
};

// 节流函数
const throttle = (fn: Function, delay: number) => {
  let lastCall = 0;
  return function (...args: any[]) {
    const now = Date.now();
    if (now - lastCall >= delay) {
      fn.apply(null, args);
      lastCall = now;
    }
  };
};

interface LineageGraphProps {
  /**
   * 布局
   */
  layout: any;
  /**
   * 血缘数据
   */
  lineageData: any;
  /**
   * 水印文字
   */
  textWaterMarker: string;
  /**
   * 高亮颜色
   */
  highlightColor: string;
  /**
   * 编辑器是否收起
   */
  isEditorCollapsed?: boolean;
  /**
   * 画布宽度
   */
  canvasWidth?: number;
  /**
   * 画布高度。不传时退回按整屏高度估算 —— 那个估算只在「导航在顶部、
   * 图占满剩下整屏」的老布局下成立
   */
  canvasHeight?: number;
  /**
   * 是否显示缩略图
   */
  showMinimap?: boolean;
}

const props = withDefaults(defineProps<LineageGraphProps>(), {
  isEditorCollapsed: false,
  canvasWidth: 0,
  canvasHeight: 0,
  showMinimap: false
});
const emit = defineEmits(['update:nodeSize', 'update:nodeLevel', 'selectNode']);

const canvasWrapper = ref();
const graphRef = ref();
const fieldCheckedRef = ref(true);
const wholeCheckedRef = ref(true);
const topBarRef = ref(); // 或者使用正确的类型代替any
const toolbarRef = ref(); // 或者使用正确的类型代替any
const currentHighlightColorRef = ref(props.highlightColor);
const HEADER_FOOTER_HEIGHT = 100; // 头部和底部的总高度
const lineageWholeData = ref();
const lineagePartData = ref();
const nodeSize = ref<number>(0);
const nodeLevel = ref<number>(0);
const currentLevel = ref<number>(1); // 当前选择的层级
const maxLevel = ref<number>(1); // 最大层级

onMounted(() => {
  if (!graphRef.value) {
    initGraph();
  }

  if (graphRef.value) {
    const graph = graphRef.value as any;
    // 设置文字水印
    graph.setTextWaterMarker(props.textWaterMarker);
    bindEvents(graph);
  }

  // 补画一次「挂载前就已经有的数据」。
  //
  // 下面那个 lineageData 的 watch 带 immediate:true，会在 setup 阶段就跑一次 ——
  // 那时 initGraph() 还没执行，graphRef 是 null，renderGraph 等于空转。
  // SQL 解析页看不出问题：用户是挂载之后才点解析，watch 会再触发一次。
  // 但表详情的血缘图页签是「数据先到位、再 v-if 挂载组件」，唯一一次渲染时机就是那次空转，
  // 于是画布永远是空的。这里在图实例建好后重放一次。
  renderCurrentData();

  const onFullscreenChange = () => {
    if (screenfull.isFullscreen) {
      // 全屏时使用屏幕尺寸
      const width = window.innerWidth;
      const height = window.innerHeight;
      resizeGraph(width, height);
    } else {
      // 退出全屏时，恢复原来的尺寸
      resizeGraph(computeWidth(), computeHeight());
    }
  };
  screenfull.on('change', onFullscreenChange);

  // 使用 ResizeObserver 监听容器大小变化
  // 使用之前定义的节流函数
  const throttledResize = throttle(() => {
    if (!screenfull.isFullscreen) {
      resizeGraph();
    }
  }, 16); // 约60fps

  const resizeObserver = new ResizeObserver(throttledResize);

  if (canvasWrapper.value) {
    resizeObserver.observe(canvasWrapper.value);
  }

  // 在组件卸载时清理
  onUnmounted(() => {
    if (canvasWrapper.value) {
      resizeObserver.unobserve(canvasWrapper.value);
    }
    resizeObserver.disconnect();
    screenfull.off('change', onFullscreenChange);
  });
});

/**
 * 画布尺寸。
 *
 * G6 要显式给宽高。这两个函数以前在四个地方各写了一遍，其中两处减 100、
 * 两处用 window.outerHeight 减 141，同一个画布在不同触发路径下算出不同高度。
 */
const computeWidth = () =>
    props.canvasWidth || (props.layout === 'preview'
        ? document.documentElement.clientWidth
        : document.documentElement.clientWidth - (props.isEditorCollapsed ? 40 : 340));

const computeHeight = () =>
    props.canvasHeight || window.innerHeight - HEADER_FOOTER_HEIGHT;

const initGraph = () => {
  // 实例化 Minimap
  const minimap = new G6.Minimap({
    size: [150, 100],
    refresh: true, // 启用自动刷新
    delegateStyle: {
      fill: '#40a9ff',
      stroke: '#1890ff'
    }
  });
  // TODO 报错
  // 工具栏
  const toolbar = new G6.ToolBar({
    getContent: () => {
      return toRaw(toolbarRef.value) || '';
    },
  });
  //网格画布
  const grid = new G6.Grid();
  const container: any = canvasWrapper.value;
  const width = computeWidth();
  const height = computeHeight();
  // 实例化 Graph
  graphRef.value = new G6.Graph({
    container: container || '',
    width: width,
    height: height,
    plugins: props.showMinimap ? [grid, minimap, toolbar] : [grid, toolbar],
    fitView: true,
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node'],
    },
    // 布局配置
    layout: {
      type: 'lineageLayout',
      controlPoints: true,
      nodesep: 80,
      ranksep: 300,
      begin: [1000, 1000],
      autoLayout: true,
      maxHeightRatio: 3,
    },
    defaultNode: {
      type: 'dice-er-box',
      color: '#096DD9',
      boxStyle: {
        stroke: '#096DD9',
        lineWidth: 6,
        radius: 4,
      },
      style: {
        fill: '#096DD9',
      },
      labelCfg: {
        style: {
          fill: '#ffffff',
          fontSize: 20,
        },
      },
      // 动态计算节点大小（G6 运行时支持函数，官方类型只写了 number | number[]）
      size: ((node: any) => {
        const tableName = node.id || '';
        const fields = node.fields || [];
        const maxFieldLength = Math.max(...fields.map((field: string) => field.length));
        const maxLength = Math.max(tableName.length, maxFieldLength, fields.length * 20);
        const width = Math.max(300, maxLength * 10); // 确保最小宽度为300
        const height = Math.max(100, (fields.length + 1) * 30); // 确保最小高度为100
        return [width, height];
      }) as unknown as number[],
    },
    defaultEdge: {
      type: 'dice-er-edge',
      style: {
        stroke: '#6C6B6B',
        lineWidth: 2,
        endArrow: true,
      },
    },
  });

  if (graphRef.value) {
    const graph = graphRef.value as any;
    // 设置文字水印
    graph.setTextWaterMarker(props.textWaterMarker);
    bindEvents(graph);
  }
};

// 优化数据监听，使用防抖处理大量数据更新
const debouncedDataUpdate = debounce((lineageData: any) => {
  if (lineageData) {
    // 确保数据存在并有正确的结构
    const wholeData = lineageData.withProcessData || { size: 0, level: 0, data: [] };
    const partData = lineageData.noProcessData || { size: 0, level: 0, data: [] };

    lineageWholeData.value = wholeData;
    lineagePartData.value = partData;

    // 安全地访问属性
    nodeSize.value = wholeData.size || 0;
    nodeLevel.value = wholeData.level || 0;

    // 发出事件通知父组件
    emit('update:nodeSize', nodeSize.value);
    emit('update:nodeLevel', nodeLevel.value);

    // 使用 requestAnimationFrame 优化渲染
    if (wholeData.data && wholeData.data.length > 0) {
      requestAnimationFrame(() => {
        const data = transformData(wholeData.data,true);
        renderGraph(toRaw(graphRef.value), data);
      });
    }
  }
}, 16);

/**
 * 把当前 props.lineageData 画到图上。
 *
 * 抽出来是为了让「数据变化」与「图实例刚建好」两个时机走同一条路径，
 * 不然两边逻辑迟早会走散。graphRef 还没建好时直接跳过，由 onMounted 补画。
 */
const renderCurrentData = () => {
  const wholeData = lineageWholeData.value;
  if (!graphRef.value || !wholeData?.data?.length) {
    return;
  }
  renderGraph(toRaw(graphRef.value), transformData(wholeData.data, true));
};

watch(
    () => props.lineageData,
    (lineageData) => {
      // 重置开关状态
      fieldCheckedRef.value = true;
      wholeCheckedRef.value = true;

      if (lineageData) {
        // 确保数据存在并有正确的结构
        const wholeData = lineageData.withProcessData || { size: 0, level: 0, data: [] };
        const partData = lineageData.noProcessData || { size: 0, level: 0, data: [] };

        lineageWholeData.value = wholeData;
        lineagePartData.value = partData;

        // 安全地访问属性
        nodeSize.value = wholeData.size || 0;
        nodeLevel.value = wholeData.level || 0;

        // 设置最大层级和当前层级
        const newMaxLevel = wholeData.level || 1;
        maxLevel.value = newMaxLevel;
        // 重要：每次数据更新时，将当前层级设置为最大层级
        currentLevel.value = newMaxLevel;

        // 发出事件通知父组件
        emit('update:nodeSize', nodeSize.value);
        emit('update:nodeLevel', nodeLevel.value);

        // 确保数据存在再渲染。图实例尚未创建时（immediate 首次触发）这里会跳过，
        // 由 onMounted 里的 renderCurrentData() 补上
        renderCurrentData();
      }
    },
    { immediate: true } // 立即执行一次监听函数
);

watch(
    () => props.textWaterMarker,
    (newValue) => {
      handleTextWaterMarker(graphRef.value, newValue);
    }
);

watch(
    () => props.highlightColor,
    (newValue) => {
      currentHighlightColorRef.value = newValue;
      handleHighlightColor(graphRef.value, newValue);
    }
);

// 监听 showMinimap 的变化
watch(
    () => props.showMinimap,
    (newValue) => {
      if (graphRef.value) {
        // 重新初始化图表以更新插件配置
        graphRef.value.destroy();
        initGraph();
        // 如果有数据，重新渲染
        if (lineageWholeData.value?.data?.length > 0) {
          const data = transformData(lineageWholeData.value.data);
          renderGraph(toRaw(graphRef.value), data);
        }
      }
    }
);

// 监听画布宽度变化
watch(
    () => props.canvasWidth,
    (newWidth) => {
      if (graphRef.value && newWidth > 0) {
        const windowHeight = document.documentElement.clientHeight;
        const height = window.outerHeight - 141 || windowHeight;
        graphRef.value.changeSize(newWidth, height);
        graphRef.value.fitView();
      }
    }
);

// 监听编辑器收起状态变化
watch(
    () => props.isEditorCollapsed,
    (isCollapsed) => {
      if (graphRef.value) {
        graphRef.value.changeSize(computeWidth(), computeHeight());
        graphRef.value.fitView();
      }
    }
);

// 容器尺寸由外部（AppLayout 量的内容区）驱动时，跟着改画布
watch(
    () => [props.canvasWidth, props.canvasHeight],
    () => {
      if (graphRef.value && !screenfull.isFullscreen) {
        resizeGraph(computeWidth(), computeHeight());
      }
    }
);

// 监听缩略图显示状态
watch(
    () => props.showMinimap,
    (showMinimap) => {
      if (graphRef.value) {
        const graph = graphRef.value;
        const plugins = graph.get('plugins');
        const minimapPlugin = plugins.find((p: any) => p.name === 'minimap');

        if (minimapPlugin) {
          if (showMinimap) {
            minimapPlugin.show();
          } else {
            minimapPlugin.hide();
          }
        }
      }
    }
);

watch(
    () => props.layout,
    (val) => {
      if (graphRef.value) {
        graphRef.value.changeSize(computeWidth(), computeHeight());
        graphRef.value.fitView();
      }
    }
);

// 更改canvas宽高
const handleChangeSize = (width: any, height: any) => {
  requestAnimationFrame(() => {
    resizeGraph(width, height);
  });
};

// 调整图表大小
const resizeGraph = (width?: number, height?: number) => {
  if (!graphRef.value) return;

  const graph = graphRef.value;
  const container = canvasWrapper.value;

  if (!width || !height) {
    // 如果没有提供宽高，则自动计算
    if (screenfull.isFullscreen) {
      width = window.innerWidth;
      height = window.innerHeight;
    } else {
      width = computeWidth();
      height = computeHeight();
    }
  }

  graph.changeSize(width, height);

  // 更新缩略图
  if (props.showMinimap) {
    const plugins = graph.get('plugins');
    const minimapPlugin = plugins.find((p: any) => p.name === 'minimap');
    if (minimapPlugin) {
      minimapPlugin.updateCanvas();
    }
  }

  graph.fitView();
};

const bindEvents = (graph: any) => {
  let lastClickTime = 0;
  const doubleClickDelay = 300; // 双击判定的时间间隔（毫秒）
  let isDoubleClick = false;

  // 监听节点点击事件
  graph.off('node:click').on('node:click', (evt: any) => {
    const currentTime = new Date().getTime();
    const { item, target } = evt;
    const model = item.getModel();
    let currentAnchor = target.get('name');

    // 如果没有获取到名称，或者名称与节点ID或标签相同，则认为点击的是表名
    if (!currentAnchor) {
      // 检查是否点击的是表头区域
      const shape = target.get('type');
      if (shape === 'rect' || shape === 'text') {
        // 判断是否点击的是表头区域
        const y = evt.y;
        const bbox = target.getBBox();
        // 如果点击位置在表头区域内，则认为点击的是表名
        if (bbox && y <= bbox.minY + 42) { // 42是表头高度
          currentAnchor = model.label || model.id;
        } else {
          return; // 如果点击的是其他区域，不做处理
        }
      } else {
        return; // 如果点击的不是矩形或文本元素，不做处理
      }
    }

    if (currentTime - lastClickTime < doubleClickDelay) {
      // 这是双击的第二次点击，标记为双击并阻止后续操作
      isDoubleClick = true;
      setTimeout(() => {
        isDoubleClick = false;
      }, doubleClickDelay);
    } else {
      // 这是单击或双击的第一次点击
      setTimeout(() => {
        if (!isDoubleClick) {
          // 确认是单击事件才触发
          if (fieldCheckedRef.value) {
            handleNodeClick(graph, item, currentAnchor, 'highlight');
          } else {
            handleNodeClick(graph, item, currentAnchor, 'tableHighlight');
          }
        }
      }, doubleClickDelay);
    }
    lastClickTime = currentTime;
  });

  // 监听连线点击事件
  graph.off('edge:click').on('edge:click', (evt: any) => {
    const currentTime = new Date().getTime();
    const { item } = evt;

    if (currentTime - lastClickTime < doubleClickDelay) {
      // 这是双击的第二次点击，标记为双击并阻止后续操作
      isDoubleClick = true;
      setTimeout(() => {
        isDoubleClick = false;
      }, doubleClickDelay);
    } else {
      // 这是单击或双击的第一次点击
      setTimeout(() => {
        if (!isDoubleClick) {
          // 确认是单击事件才触发
          if (fieldCheckedRef.value) {
            handleEdgeClick(graph, item, 'highlight');
          } else {
            handleEdgeClick(graph, item, 'tableHighlight');
          }
        }
      }, doubleClickDelay);
    }
    lastClickTime = currentTime;
  });

  //监听只在 canvas 空白处点击事件
  graph.off('canvas:click').on('canvas:click', (ev: any) => {
    // 清除状态
    clearAllStats(graph);
  });

  // 监听节点双击事件
  graph.off('node:dblclick').on('node:dblclick', (evt: any) => {
    // 双击事件处理，只阻止事件冒泡
    evt.stopPropagation();
  });

  // 监听边双击事件
  graph.off('edge:dblclick').on('edge:dblclick', (evt: any) => {
    // 双击事件处理，只阻止事件冒泡
    evt.stopPropagation();
  });
};

/**
 * 处理节点点击事件
 */
const handleNodeClick = (
    graph: any,
    item: any,
    currentAnchor: string,
    name: string
) => {
  // 清除所有高亮状态
  clearAllStats(graph);

  const model = item.getModel();
  const edges = item.getEdges();
  const leftActiveEdges: any[] = [];
  const rightActiveEdges: any[] = [];

  // 获取左关联边及节点
  getLeftRelation(edges, model, currentAnchor, leftActiveEdges);

  // 获取右关联边及节点
  getRightRelation(edges, model, currentAnchor, rightActiveEdges);

  // 打印需要高亮的字段
  console.log("需要高亮的字段:");
  console.log("当前字段:", { tableName: model.id, fieldName: currentAnchor });

  // 设置当前节点状态
  graph.setItemState(item, name + '-' + currentAnchor, true);

  // 设置左关联边及节点状态
  setLeftStats(graph, leftActiveEdges, currentHighlightColorRef.value, name);

  // 设置右关联边及节点状态
  setRightStats(graph, rightActiveEdges, currentHighlightColorRef.value, name);

  // 修改当前字段的颜色并记录原始颜色
  if (currentAnchor) {
    const updatedAttrs = model.attrs.map((attr: any) => {
      if (attr.key === currentAnchor) {
        attr.color = '#FF5733'; // 设置新的颜色
      }
      return attr;
    });
    graph.updateItem(item, { model: { attrs: updatedAttrs } }); // 更新 model 中的 attrs
    graph.refreshItem(item); // 强制刷新节点
  }

  // 触发选择节点事件
  emit('selectNode', { tableName: model.id, fieldName: currentAnchor });
};

/**
 * 处理连线点击事件
 */
const handleEdgeClick = (graph: any, item: any, name: string) => {
  // 清除所有高亮状态
  clearAllStats(graph);

  const sourceNode = item.getSource();
  const sourceModel = sourceNode.getModel();
  const sourceEdges = sourceNode.getInEdges();
  // 获取当前连线的 source 节点
  const sourceAnchor = item.getModel()['sourceAnchor'];

  const leftActiveEdges: any[] = [];
  leftActiveEdges.push(item);

  getLeftRelation(sourceEdges, sourceModel, sourceAnchor, leftActiveEdges);

  const targetNode = item.getTarget();
  const targetModel = targetNode.getModel();

  // 获取当前连线的 target 节点
  const targetAnchor = item.getModel()['targetAnchor'];

  const rightActiveEdges: any[] = [];
  rightActiveEdges.push(item);

  // 设置左关联边及节点状态
  setLeftStats(graph, leftActiveEdges, currentHighlightColorRef.value, name);

  // 设置右关联边及节点状态
  setRightStats(graph, rightActiveEdges, currentHighlightColorRef.value, name);

  // 触发选择节点事件，使用源节点和目标节点的信息
  // 优先使用目标节点的信息，因为通常是从源到目标的数据流
  emit('selectNode', {
    tableName: targetModel.id || sourceModel.id,
    fieldName: targetAnchor || sourceAnchor
  });
};

/**
 * 处理字段血缘切换
 */
const onFieldLineage = (checked: boolean) => {
  fieldCheckedRef.value = checked;
  if (!lineageWholeData.value || !lineagePartData.value) {
    return;
  }
  let data: any;
  let size: number = 0;
  let level: number = 0;

  if (wholeCheckedRef.value) {
    size = lineageWholeData.value.size || 0;
    level = lineageWholeData.value.level || 0;
  } else {
    size = lineagePartData.value.size || 0;
    level = lineagePartData.value.level || 0;
  }

  if (checked) {
    if (wholeCheckedRef.value) {
      data = transformData(lineageWholeData.value.data);
    } else {
      data = transformData(lineagePartData.value.data);
    }
  } else {
    if (wholeCheckedRef.value) {
      data = collapseData(lineageWholeData.value.data);
    } else {
      data = collapseData(lineagePartData.value.data);
    }
  }
  emit('update:nodeLevel', level);
  emit('update:nodeSize', size);
  renderGraph(graphRef.value, data);
};

/**
 * 处理完整血缘链路切换
 */
const onWholeLineage = (checked: boolean) => {
  wholeCheckedRef.value = checked;
  if (!lineageWholeData.value || !lineagePartData.value) {
    return;
  }
  let data: any;
  let size: number = 0;
  let level: number = 0;
  if (checked) {
    size = lineageWholeData.value.size || 0;
    level = lineageWholeData.value.level || 0;
    maxLevel.value = level;
    if (fieldCheckedRef.value) {
      data = transformData(lineageWholeData.value.data);
    } else {
      data = collapseData(lineageWholeData.value.data);
    }
  } else {
    size = lineagePartData.value.size || 0;
    level = lineagePartData.value.level || 0;
    maxLevel.value = level;
    if (fieldCheckedRef.value) {
      data = transformData(lineagePartData.value.data);
    } else {
      data = collapseData(lineagePartData.value.data);
    }
  }
  emit('update:nodeLevel', level);
  emit('update:nodeSize', size);
  renderGraph(graphRef.value, data);
};

/**
 * 处理层级变化
 */
const onLevelChange = (selectedLevel: number) => {
  currentLevel.value = selectedLevel;

  let data: any;
  const currentData = wholeCheckedRef.value ? lineageWholeData.value : lineagePartData.value;

  if (!currentData || !currentData.data) return;

  // 过滤数据，保留level小于选定层级的节点
  const filteredData = currentData.data.filter((item: any) => {
    // 检查targetField中的level属性
    if (item.targetField && typeof item.targetField.level === 'number') {
      if (item.targetField.level < selectedLevel) {
        return true;
      }
    }

    // 检查refFields中的level属性
    if (item.refFields && Array.isArray(item.refFields)) {
      // 如果任何一个refField的level小于选择层级，就保留该节点
      for (const refField of item.refFields) {
        if (refField && typeof refField.level === 'number' && refField.level < selectedLevel) {
          return true;
        }
      }
    }

    // 如果没有直接的level属性，尝试从表名中提取
    if (item.targetField && item.targetField.fieldName) {
      // 从字符串中提取level信息
      const match = item.targetField.fieldName.match(/(\d+)-\d+:/);
      if (match) {
        const nodeLevel = parseInt(match[1]);
        return nodeLevel < selectedLevel;
      }
    }

    // 同样检查refFields中的fieldName
    if (item.refFields && Array.isArray(item.refFields)) {
      for (const refField of item.refFields) {
        if (refField && refField.fieldName) {
          const match = refField.fieldName.match(/(\d+)-\d+:/);
          if (match) {
            const nodeLevel = parseInt(match[1]);
            if (nodeLevel < selectedLevel) {
              return true;
            }
          }
        }
      }
    }

    return false; // 如果所有检查都未通过，则不保留该节点
  });

  // 转换数据并渲染
  if (fieldCheckedRef.value) {
    data = transformData(filteredData);
  } else {
    data = collapseData(filteredData);
  }

  renderGraph(graphRef.value, data);
};
</script>

<style scoped>
@import url("./index.css");

:-webkit-full-screen {
  background-color: #ffffff !important;
}
:-moz-full-screen {
  background-color: #ffffff !important;
}

:-ms-fullscreen {
  background-color: #ffffff !important;
}
:fullscreen {
  background-color: #ffffff !important;
}
</style>