import { Base } from '@antv/layout/lib/layout/base';

import { DagreLayoutOptions, Node } from '@antv/layout/lib/layout/types';
import { maxLevel, nodeWidth } from '../../registerShape';

/**
 * 默认从左到右（maxLayer--->minLayer)
 * 默认居中对齐
 */
class CustomDagreLayout extends Base {
  /** 布局的起始（左上角）位置 */
  public begin: number[] = [0, 0];

  /** 节点水平间距(px) */
  public nodesep: number = 80;

  /** 层级间距 */
  public ranksep: number = 350;

  /** 最小层级间距 */
  public minRanksep: number = 300;

  /** 是否自动调整布局 */
  public autoLayout: boolean = true;

  /** 最大节点高度比例 */
  public maxHeightRatio: number = 3;

  /** 宽节点阈值 */
  public wideNodeThreshold: number = 250;

  constructor(options?: DagreLayoutOptions) {
    super();
    this.updateCfg(options);
  }

  public getDefaultCfg() {
    return {
      nodesep: 80, // 节点水平间距(px)
      ranksep: 350, // 层级间距
      minRanksep: 300, // 最小层级间距
      begin: [0, 0], // 布局的起点位置
      autoLayout: true, // 是否自动调整布局
      maxHeightRatio: 3, // 最大节点高度比例
      wideNodeThreshold: 250, // 宽节点阈值
    };
  }

  /**
   * 执行布局
   */
  public execute() {
    const self = this;
    const { nodes, edges, ranksep, nodesep, begin, autoLayout, maxHeightRatio, minRanksep, wideNodeThreshold } = self;
    if (!nodes) return;

    // 预处理：构建节点连接关系图
    const nodeConnections = new Map<string, Set<string>>();
    const edgesBySource = new Map<string, any[]>();
    const edgesByTarget = new Map<string, any[]>();

    // 初始化节点连接集合
    nodes.forEach((node: any) => {
      nodeConnections.set(node.id, new Set());
    });

    // 构建边的索引
    edges?.forEach((edge: any) => {
      const sourceId = edge.source;
      const targetId = edge.target;

      // 记录节点间的连接关系
      const sourceConnections = nodeConnections.get(sourceId) || new Set();
      sourceConnections.add(targetId);
      nodeConnections.set(sourceId, sourceConnections);

      const targetConnections = nodeConnections.get(targetId) || new Set();
      targetConnections.add(sourceId);
      nodeConnections.set(targetId, targetConnections);

      // 按源节点索引边
      if (!edgesBySource.has(sourceId)) {
        edgesBySource.set(sourceId, []);
      }
      edgesBySource.get(sourceId)!.push(edge);

      // 按目标节点索引边
      if (!edgesByTarget.has(targetId)) {
        edgesByTarget.set(targetId, []);
      }
      edgesByTarget.get(targetId)!.push(edge);
    });

    // 计算节点的连接权重和位置影响
    nodes.forEach((node: any) => {
      const connections = nodeConnections.get(node.id) || new Set();
      const connectionWeight = connections.size;

      // 将连接权重纳入order计算
      node.connectionWeight = connectionWeight;

      // 初始化order，后续会根据层级关系进一步调整
      if (node.order === undefined) {
        node.order = 0;
      }
    });

    const layerMap: Map<number, Node[]> = new Map();
    nodes.forEach((item: any, index, arr) => {
      if (!layerMap.has(item.level)) {
        layerMap.set(
          item.level,
          arr.filter((node: any) => node.level === item.level)
        );
      }
    });

    // 检查是否有不平衡的层级
    const layerSizes = Array.from(layerMap.entries()).map(([level, nodes]) => ({
      level,
      count: nodes.length
    }));

    const maxNodesInLayer = Math.max(...layerSizes.map(item => item.count));
    const minNodesInLayer = Math.min(...layerSizes.map(item => item.count));
    const isUnbalanced = maxNodesInLayer > minNodesInLayer * maxHeightRatio;

    // 调整节点间距，处理不平衡情况
    let adjustedNodesep = nodesep;
    if (autoLayout && isUnbalanced) {
      // 对于节点数量多的层级，减小节点间距
      const largestLayer = layerSizes.find(item => item.count === maxNodesInLayer);
      if (largestLayer && largestLayer.count > 15) {
        // 动态调整节点间距，节点越多间距越小
        adjustedNodesep = Math.max(20, nodesep * (15 / largestLayer.count));
      }
    }

    const startX = begin[0];
    const startY = begin[1];
    const size = layerMap.size;
    const maxWidth = size * nodeWidth + (size - 1) * ranksep;

    // 计算每层的高度
    const layerHeights = new Map<number, number>();
    layerMap.forEach((nodes, level) => {
      const totalHeight = nodes.reduce((sum: number, node: any) => sum + node.size[1], 0) +
                         (nodes.length - 1) * adjustedNodesep;
      layerHeights.set(level, totalHeight);
    });

    const maxHeight = Math.max(...Array.from(layerHeights.values()));
    const offsetX = startX + maxWidth;
    const offsetY = startY + maxHeight;
    const centerLine = offsetY - maxHeight / 2;

    // 计算每一层的垂直中心位置
    const layerCenters = new Map<number, number>();
    layerMap.forEach((value, key) => {
      const layerHeight = layerHeights.get(key) || 0;
      layerCenters.set(key, centerLine - layerHeight / 2);
    });

    // 处理水平位置
    const layerWidths = new Map<number, number>();
    layerMap.forEach((nodes, level) => {
      // 计算层的宽度
      const maxNodeWidth = Math.max(...nodes.map((node: any) => node.size[0]));
      layerWidths.set(level, maxNodeWidth);
    });

    let accumulatedX = offsetX;
    layerMap.forEach((value, key) => {
      let d = key === maxLevel ? size - 1 : key;

      // 动态调整层间距
      let currentRanksep = ranksep;
      const currentLayerSize = value.length;
      const hasWideNodes = value.some((node: any) => node.size[0] > wideNodeThreshold);

      // 特殊处理第一层和第二层之间的间距
      const isFirstLayer = key === 0;
      const isSecondLayer = key === 1;

      if (autoLayout) {
        // 根据节点数量和宽度调整层间距
        if (currentLayerSize > 15 || hasWideNodes) {
          // 基础增加系数
          let increaseRatio = 1 + currentLayerSize / 30;

          // 如果存在宽节点，进一步增加间距
          if (hasWideNodes) {
            increaseRatio += 0.3;
          }

          // 第一层和第二层之间需要更多空间
          if (isFirstLayer || isSecondLayer) {
            increaseRatio += 0.2;
          }

          // 计算新的层间距，确保不小于最小值
          currentRanksep = Math.max(
            minRanksep,
            Math.min(ranksep * 2.5, ranksep * increaseRatio)
          );
        } else if (isFirstLayer || isSecondLayer) {
          // 即使节点数量不多，也确保第一层和第二层之间有足够空间
          currentRanksep = Math.max(currentRanksep, ranksep * 1.2);
        }
      }

      const x = offsetX - d * (nodeWidth + currentRanksep);
      const layerCenter = layerCenters.get(key) || centerLine;

      // 特殊处理第一层的排序，使其与第二层的连接更加整齐
      const isLastLayer = key === maxLevel;

      // 如果是第一层，我们需要特别优化其排序以便与第二层更好地对齐
      if (isFirstLayer && layerMap.has(1)) {
        const secondLayerNodes = layerMap.get(1) || [];

        // 计算第一层节点的新order值，基于它们与第二层节点的连接
        value.forEach((node: any) => {
          // 获取该节点连接到的第二层节点
          const connections = nodeConnections.get(node.id) || new Set();
          let connectedSecondLayerOrder = 0;
          let connectionCount = 0;

          // 计算连接到的第二层节点的平均order
          secondLayerNodes.forEach((secondNode: any) => {
            if (connections.has(secondNode.id)) {
              connectedSecondLayerOrder += secondNode.order;
              connectionCount++;
            }
          });

          // 更新节点order，优先考虑与第二层的连接
          if (connectionCount > 0) {
            node.order = connectedSecondLayerOrder / connectionCount;
          }
        });
      }

      const sortNodes = value.sort((a: any, b: any) => {
        if (!isLastLayer) {
          // 所有层级（除了最后一层）都按照连接的节点位置进行排序
          return a.order - b.order;
        }
        return b.order - a.order;
      });

      // 动态调整节点间距
      let currentNodesep = value.length > 15 ? adjustedNodesep : nodesep;

      // 如果存在宽节点，适当增加节点间距
      if (hasWideNodes) {
        currentNodesep = Math.max(currentNodesep, nodesep * 1.2);
      }

      let currentY = layerCenter;
      sortNodes.forEach((node: any, index) => {
        const margin = index === 0 ? 0 : currentNodesep;
        currentY += margin;
        node.x = x;
        node.y = currentY;
        currentY += node.size[1];
      });
    });
    if (self.onLayoutEnd) self.onLayoutEnd();
  }

  public getType() {
    return 'lineageLayout';
  }
}

export default CustomDagreLayout;
