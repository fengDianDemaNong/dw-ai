/**
 * 放大
 * @param graph
 */
export const handleZoomOut = (graph: any) => {
  if (!graph) {
    return;
  }
  try {
    const current = graph.getZoom();
    const canvas = graph.get('canvas');
    if (!canvas) {
      return;
    }
    const point = canvas.getPointByClient(
      canvas.get('width') / 2,
      canvas.get('height') / 2
    );
    const pixelRatio = canvas.get('pixelRatio') || 1;

    // 根据当前缩放级别使用不同的放大比例
    let ratio;
    if (current < 0.1) {
      ratio = 1.3; // 当缩放比例小于10%时，每次放大30%
    } else if (current < 0.2) {
      ratio = 1.25; // 当缩放比例在10%-20%之间时，每次放大25%
    } else if (current < 0.5) {
      ratio = 1.2; // 当缩放比例在20%-50%之间时，每次放大20%
    } else if (current < 1) {
      ratio = 1.15; // 当缩放比例在50%-100%之间时，每次放大15%
    } else {
      ratio = 1.1; // 当缩放比例大于100%时，每次放大10%
    }

    const newZoom = ratio * current;

    // 设置最大缩放限制为300%
    if (newZoom > 3) {
      return;
    }

    graph.zoom(ratio, {
      x: point.x / pixelRatio,
      y: point.y / pixelRatio,
    });

    const result = {
      text: `${Math.round(newZoom * 100)}%`,
      ratio: newZoom,
    };
    return result;
  } catch (error) {
    // 保留关键错误日志
    console.error('Error in handleZoomOut:', error);
  }
};

/**
 * 缩小
 * @param graph
 */
export const handleZoomIn = (graph: any) => {
  if (!graph) {
    return;
  }
  try {
    const current = graph.getZoom();
    const canvas = graph.get('canvas');
    if (!canvas) {
      return;
    }
    const point = canvas.getPointByClient(
      canvas.get('width') / 2,
      canvas.get('height') / 2
    );
    const pixelRatio = canvas.get('pixelRatio') || 1;

    // 根据当前缩放级别使用不同的缩放比例
    let ratio;
    if (current >= 1) {
      ratio = 0.8; // 当缩放比例大于等于100%时，每次缩小20%
    } else if (current >= 0.5) {
      ratio = 0.85; // 当缩放比例在50%-100%之间时，每次缩小15%
    } else if (current >= 0.2) {
      ratio = 0.9; // 当缩放比例在20%-50%之间时，每次缩小10%
    } else if (current >= 0.1) {
      ratio = 0.95; // 当缩放比例在10%-20%之间时，每次缩小5%
    } else {
      ratio = 0.98; // 当缩放比例小于10%时，每次缩小2%
    }

    const newZoom = ratio * current;

    // 设置最小缩放限制为5%
    if (newZoom < 0.05) {
      return;
    }

    graph.zoom(ratio, {
      x: point.x / pixelRatio,
      y: point.y / pixelRatio,
    });

    const result = {
      text: `${Math.round(newZoom * 100)}%`,
      ratio: newZoom,
    };
    return result;
  } catch (error) {
    // 保留关键错误日志
    console.error('Error in handleZoomIn:', error);
  }
};

/**
 * 实际大小
 * @param graph
 */
export const handleRealZoom = (graph: any) => {
  if (!graph) return;
  const current = graph.getZoom();
  graph.zoom(1 / current);
  graph.fitCenter();
  return {
    text: '100%',
    ratio: 1,
  };
};

/**
 * 自适应canvas大小
 */
export const handleAutoZoom = (graph: any) => {
  if (!graph) return;
  const nodes = graph.getNodes();
  if (nodes.length > 0) {
    graph.fitView([20, 20]);
  }
  const current = graph.getZoom();
  return {
    text: `${Math.round(current * 100)}%`,
    ratio: current,
  };
};

/**
 * 推拽后恢复布局
 * @param graph
 */
export const handleRefreshLayout = (graph: any) => {
  if (!graph) return;

  // 暂停画布渲染
  graph.setAutoPaint(false);

  // 重新布局
  graph.layout();

  // 适配视图
  graph.fitView([20, 20]);

  // 恢复画布渲染并重绘
  graph.paint();
  graph.setAutoPaint(true);

  // 返回当前缩放比例
  const current = graph.getZoom();
  return {
    text: `${Math.round(current * 100)}%`,
    ratio: current,
  };
};

/**
 * 下载图片
 * @param graph
 */
// 添加一个简单的防抖函数
const debounce = (func: Function, wait: number) => {
  let timeout: ReturnType<typeof setTimeout> | null = null;
  return function(this: any, ...args: any[]) {
    const context = this;
    if (timeout !== null) clearTimeout(timeout);
    timeout = setTimeout(() => func.apply(context, args), wait);
  };
};

export const handleDownloadImage = debounce((graph: any) => {
  if (!graph) return;

  try {
    // 获取当前视图状态
    const currentZoom = graph.getZoom();
    const currentTransform = graph.getGroup().getMatrix();

    // 暂时禁用动画
    const animate = graph.get('animate');
    graph.set('animate', false);

    // 缓存当前视口信息
    const viewportCache = {
      zoom: currentZoom,
      matrix: [...currentTransform]
    };

    // 计算合适的放大倍数
    const scale = Math.min(5, 10 / currentZoom); // 最大放大5倍，避免内存问题

    // 调整视图以获得更高质量的图片
    graph.zoomTo(currentZoom * scale);

    // 获取level=0的节点
    const nodes = graph.getNodes();
    const level0Node = nodes.find((node: any) => node.getModel().level === 0);
    let fileName = 'sql-analyze';
    if (level0Node) {
      fileName = level0Node.getModel().id || 'sql-analyze';
    }

    // 使用Promise处理异步下载
    Promise.resolve()
      .then(() => {
        // 下载图片
        graph.downloadFullImage(fileName, 'image/png', {
          padding: [50, 30, 30, 30],
          backgroundColor: '#FFFFFF',
          pixelRatio: 2,
          quality: 1,
        });
      })
      .finally(() => {
        // 恢复原始视图状态
        requestAnimationFrame(() => {
          // 恢复动画设置
          graph.set('animate', animate);

          // 恢复原始变换
          graph.getGroup().setMatrix(viewportCache.matrix);
          graph.zoomTo(viewportCache.zoom);

          // 确保视图更新
          graph.refresh();
        });
      });
  } catch (error) {
    console.error('Download failed:', error);
    // 确保在出错时也能恢复视图
    graph.refresh();
  }
}, 300);

/**
 * 全屏查看
 */
export const handleEnterFullscreen = (container: any) => {
  if (container.requestFullscreen) {
    container.requestFullscreen();
  } else if (container.mozRequestFullScreen) {
    // Firefox
    container.mozRequestFullScreen();
  } else if (container.webkitRequestFullscreen) {
    // Chrome, Safari and Opera
    container.webkitRequestFullscreen();
  } else if (container.msRequestFullscreen) {
    // IE/Edge
    container.msRequestFullscreen();
  }
};

/**
 * 退出全屏
 */
export const handleExitFullscreen = () => {
  if (document.exitFullscreen) {
    document.exitFullscreen();
    // @ts-ignore
  } else if (document.mozCancelFullScreen) {
    // @ts-ignore Firefox
    document.mozCancelFullScreen();
    // @ts-ignore
  } else if (document.webkitExitFullscreen) {
    // @ts-ignore Chrome, Safari and Opera
    document.webkitExitFullscreen();
    // @ts-ignore
  } else if (document.msExitFullscreen) {
    // @ts-ignore IE/Edge
    document.msExitFullscreen();
  }
};

/**
 * 渲染视图
 */
export const renderGraph = (graph: any, lineageData: any) => {
  if (!graph || !lineageData) return;
  graph.data(lineageData);
  graph.render();
  graph.fitView();
};

/**
 * 设置文字水印
 * @param graph
 * @param text
 */
export const handleTextWaterMarker = (graph: any, text: string) => {
  if (!graph) return;
  graph.setTextWaterMarker(text);
};

/**
 * 设置连线高亮颜色
 * @param graph
 * @param color
 */
export const handleHighlightColor = (graph: any, color: string) => {
  if (!graph) return;
  // 查询所有选中的元素
  const edges = graph.findAll('edge', (item: any) => {
    return (
      item.getStates().length !== 0 &&
      item.getStates()[0].startsWith('highlight')
    );
  });
  if (edges) {
    edges.forEach((edge: any) =>
      graph.setItemState(edge, `highlight-${color}`, true)
    );
  }
};

/**
 * 清除状态
 */
export const clearAllStats = (graph: any) => {
  if (!graph) return;
  graph.setAutoPaint(false);
  // 清除节点状态
  graph.getNodes().forEach(function (node: any) {
    graph.clearItemStates(node);

    // 恢复字段颜色为默认颜色
    const model = node.getModel();
    if (model && model.attrs) {
      const updatedAttrs = model.attrs.map((attr: any) => {
        attr.color = attr.originalColor; // 恢复默认颜色
        return attr;
      });
      graph.updateItem(node, {
        attrs: updatedAttrs,
      });
    }
  });
  // 清除边状态
  graph.getEdges().forEach(function (edge: any) {
    graph.clearItemStates(edge);
  });
  graph.paint();
  graph.setAutoPaint(true);
};

/**
 * 设置左边关联节点及边状态
 * @param edges 边
 */
export const setLeftStats = (
  graph: any,
  edges: any[],
  color: string,
  name: string
) => {
  if (!graph) return;
  edges.forEach(function (edge: any) {
    const sourceNode = edge.getSource();
    const sourceAnchor = edge.getModel()['sourceAnchor'];

    // 确保 sourceNode 是有效的节点对象
    if (!sourceNode || !sourceNode.hasState) {
      console.error('Invalid source node:', sourceNode);
      return;
    }

    graph.setItemState(edge, `highlight-${color}`, true);
    edge.toFront();
    graph.setItemState(sourceNode, name + '-' + sourceAnchor, true);

    if (sourceAnchor) {
      // 设置上游节点字段高亮状态
      graph.setItemState(sourceNode, name + '-' + sourceAnchor, true);

      // 修改字段颜色
      const updatedAttrs = sourceNode.getModel().attrs.map((attr: any) => {
        if (attr.key === sourceAnchor) {
          attr.
color = '#FF5733';
          return attr;
        }
        return attr;
      });

      // 更新节点的 attrs 属性
      graph.updateItem(sourceNode, {
        attrs: updatedAttrs,
      });
    }
  });
};

/**
 * 设置右边关联节点及边状态
 * @param edges 边
 */
export const setRightStats = (
  graph: any,
  edges: any[],
  color: string,
  name: string
) => {
  if (!graph) return;
  edges.forEach(function (edge: any) {
    const targetNode = edge.getTarget();
    const targetAnchor = edge.getModel()['targetAnchor'];

    // 确保 targetNode 是有效的节点对象
    if (!targetNode || !targetNode.hasState) {
      console.error('Invalid target node:', targetNode);
      return;
    }

    graph.setItemState(edge, `highlight-${color}`, true);
    edge.toFront();
    graph.setItemState(targetNode, name + '-' + targetAnchor, true);

    if (targetAnchor) {
      // 设置下游节点字段高亮状态
      graph.setItemState(targetNode, name + '-' + targetAnchor, true);

      // 修改字段颜色
      const updatedAttrs = targetNode.getModel().attrs.map((attr: any) => {
        if (attr.key === targetAnchor) {
          attr.originalColor = attr.color;
          attr.color = '#FF5733';
          return attr;
        }
        return attr;
      });

      // 更新节点的 attrs 属性
      graph.updateItem(targetNode, {
        attrs: updatedAttrs,
      });
    }
  });
};
