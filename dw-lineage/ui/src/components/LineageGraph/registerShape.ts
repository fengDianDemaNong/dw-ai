import G6 from '@antv/g6';
import { message } from 'ant-design-vue';

// 行高
export const itemHeight = 42;
// 字体大小
const fontSize = 24;
// 文字 X 轴偏移量
const fontOffsetX = 12;
// 文字 Y 轴偏移量
const fontOffsetY = itemHeight / 2 + fontSize / 2;
// 边框线框
const lineWidth = 6;
// 最外层层级大小
export const maxLevel = -1;
// 节点宽度 - 默认值，实际会根据内容动态计算
export const nodeWidth = 400;

// 计算文本显示宽度的函数
const getTextWidth = (text: string, fontSize: number = 24) => {
  if (!text) return 0;

  // 创建 canvas 用于测量文本宽度
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) return text.length * fontSize * 0.6; // 降级处理

  // 设置字体
  context.font = `${fontSize}px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif`;

  // 测量文本宽度
  const metrics = context.measureText(text);
  return metrics.width;
};

const colorMap: any = {
  '0': { fill: '#F49722' },
  '-1': { fill: '#50E3C2' },
};

const handleLabelLength = (label: string) => {
  // 不再截断文本，而是完整显示
  return label || '';
};

G6.registerNode('dice-er-box', {
  draw: function draw(cfg: any, group: any) {
    const style = cfg.style;
    // 边框、底色控制
    const boxStyle = cfg.boxStyle;
    const level: string = cfg.level;
    const { attrs } = cfg;

    // 计算节点宽度：取表名、字段名和默认宽度中的最大值
    let maxWidth = nodeWidth;

    // 计算表名宽度
    const tableNameWidth = getTextWidth(cfg.label, fontSize);
    // 为表名添加额外空间，确保有足够空间放置复制图标
    const iconSpace = 70; // 为复制图标预留的空间
    maxWidth = Math.max(maxWidth, tableNameWidth + fontOffsetX * 2 + iconSpace);

    // 计算字段宽度
    if (attrs && attrs.length > 0) {
      const fieldsMaxWidth = Math.max(...attrs.map((e: any) => getTextWidth(e.key, fontSize)));
      maxWidth = Math.max(maxWidth, fieldsMaxWidth + fontOffsetX * 2);
    }

    // 如果有设置节点大小，使用设置的宽度
    if (cfg.size && Array.isArray(cfg.size)) {
      maxWidth = cfg.size[0];
    }

    const height = itemHeight * (attrs.length + 1);
    const fillColor = colorMap[level]?.fill || style.fill;
    const radius =
      attrs.length > 0
        ? [boxStyle.radius, boxStyle.radius, 0, 0]
        : boxStyle.radius;

    // 节点顶部矩形
    group.addShape('rect', {
      attrs: {
        fill: fillColor,
        height: itemHeight,
        width: maxWidth,
        radius: radius,
      },
      draggable: true,
      name: cfg.label,
    });

    // 节点顶部文本
    const headerText = group.addShape('text', {
      attrs: {
        y: fontOffsetY,
        x: fontOffsetX,
        fill: '#fff',
        text: handleLabelLength(cfg.label),
        fontSize: fontSize,
        fontWeight: 500,
        cursor: 'pointer',
      },
      draggable: true,
      name: cfg.label,
    });

    // 为表名添加双击复制功能
    headerText.on('dblclick', (evt: any) => {
      evt.stopPropagation();
      const text = evt.target.attrs.text;
      navigator.clipboard.writeText(text).then(() => {
        message.success(`已复制表名：${text}`);
      }).catch(err => {
        message.error('复制失败，请重试');
        console.error('Failed to copy table name:', err);
      });
    });

    // 计算复制图标的位置（靠右对齐）
    // 图标位置固定在节点右侧，留出一定边距
    const iconMarginRight = 20; // 距离右边界的距离
    const copyIconX = maxWidth - iconMarginRight - 10; // 10是图标半径

    // 添加复制图标背景（圆形）
    const copyIconBg = group.addShape('circle', {
      attrs: {
        x: copyIconX, // 圆心位置靠右
        y: itemHeight / 2,
        r: 10, // 合适的半径
        fill: '#ffffff', // 纯白色背景
        stroke: '#3399FF', // 添加蓝色边框
        lineWidth: 1.5,
        cursor: 'pointer',
      },
      name: 'copy-icon-bg'
    });

    // 添加更明显的复制图标（双文档样式）
    const copyIcon = group.addShape('path', {
      attrs: {
        path: [
          // 前面的文档
          ['M', copyIconX - 4, itemHeight / 2 - 5], // 左上角
          ['L', copyIconX + 2, itemHeight / 2 - 5], // 右上角
          ['L', copyIconX + 2, itemHeight / 2 + 3], // 右下角
          ['L', copyIconX - 4, itemHeight / 2 + 3], // 左下角
          ['Z'], // 闭合路径
          // 后面的文档
          ['M', copyIconX - 2, itemHeight / 2 - 3], // 左上角
          ['L', copyIconX + 4, itemHeight / 2 - 3], // 右上角
          ['L', copyIconX + 4, itemHeight / 2 + 5], // 右下角
          ['L', copyIconX - 2, itemHeight / 2 + 5], // 左下角
          ['Z'], // 闭合路径
        ],
        fill: '#3399FF', // 使用蓝色填充
        cursor: 'pointer',
      },
      name: 'copy-icon'
    });

    // 为复制图标添加点击事件
    const handleCopy = (evt: any) => {
      evt.stopPropagation(); // 阻止事件冒泡
      // 获取表名和字段列表
      const tableName = cfg.label;
      const fields = attrs.map((e: any) => e.key).join('\n');
      const copyContent = `${tableName}\n${fields}`;

      // 添加视觉反馈 - 临时改变背景色
      copyIconBg.attr('fill', '#e6f7ff');
      setTimeout(() => {
        copyIconBg.attr('fill', '#ffffff');
      }, 300);

      navigator.clipboard.writeText(copyContent).then(() => {
        message.success(`已复制表 ${tableName} 的信息`);
      }).catch(err => {
        message.error('复制失败，请重试');
        console.error('Failed to copy table info:', err);
      });
    };

    // 为所有复制相关元素添加点击事件
    copyIconBg.on('click', handleCopy);
    copyIcon.on('click', handleCopy);

    // 边框
    const keyshape = group.addShape('rect', {
      attrs: {
        x: 0,
        y: 0,
        width: maxWidth,
        height: height,
        stroke: fillColor,
        lineWidth: lineWidth,
        radius: boxStyle.radius,
        boxStyle: { ...boxStyle },
      },
      draggable: true,
    });

    const listContainer = group.addGroup({});

    if (attrs) {
      attrs.forEach((e: any, i: any) => {
        const { key } = e;
        // group部分图形控制
        listContainer.addShape('rect', {
          attrs: {
            x: 0,
            y: i * itemHeight + itemHeight,
            fill: '#ffffff',
            width: maxWidth,
            height: itemHeight,
            cursor: 'pointer',
          },
          name: key,
          draggable: true,
        });

        // group文本控制
        const fieldText = listContainer.addShape('text', {
          attrs: {
            x: fontOffsetX,
            y: (i + 1) * itemHeight + fontOffsetY,
            text: handleLabelLength(key),
            fontSize: fontSize,
            fill: e.color || '#000',  // 使用传入的颜色，如果没有则默认为黑色
            fontWeight: 500,
            cursor: 'pointer',
          },
          name: key,
          draggable: true,
        });

        // 为字段添加双击复制功能
        fieldText.on('dblclick', (evt: any) => {
          const text = evt.target.attrs.text;
          navigator.clipboard.writeText(text).then(() => {
            message.success(`已复制字段：${text}`);
          }).catch(err => {
            message.error('复制失败，请重试');
            console.error('Failed to copy field name:', err);
          });
          evt.stopPropagation();
        });
      });
    }

    return keyshape;
  },

  /**
   * 更改状态，主要用于高亮
   * @param name 状态名称
   * @param value true | false
   * @param item 要改变的节点
   */
  setState(name, value, item: any) {
    try {
      // 字段高亮
      if (name && name.startsWith('highlight')) {
        const anchor = name.split('-')[1];

        if (!item) return;
        const shape = item.get('keyShape');
        if (!shape) return;

        const model = item.getModel();
        if (!model || !model.attrs || !Array.isArray(model.attrs)) return;

        // 查找 label 下标
        const anchorIndex = model.attrs.findIndex((e: any) => e && e.key === anchor);
        if (anchorIndex === -1) return;

        // 获取父元素
        const parent = shape.get('parent');
        if (!parent) return;

        // 获取子元素集合
        const children = parent.get('children');
        if (!children || children.length < 4) return;

        // 获取列表容器
        const listContainer = children[3];
        if (!listContainer) return;

        // 获取列表容器的子元素
        const listChildren = listContainer.get('children');
        if (!listChildren || listChildren.length <= anchorIndex * 2 + 1) return;

        // 查找 label 元素，通过下标来找
        const label = listChildren[anchorIndex * 2 + 1];
        if (!label) return;

        if (value) {
          label.attr('fontWeight', 800);
        } else {
          label.attr('fontWeight', 500);
        }
      }

      // 表名称高亮
      if (name && name.startsWith('tableHighlight')) {
        if (!item) return;
        const shape = item.get('keyShape');
        if (!shape) return;

        // 获取父元素
        const parent = shape.get('parent');
        if (!parent) return;

        // 获取子元素集合
        const children = parent.get('children');
        if (!children || children.length < 2) return;

        // shape.get('parent').get('children')[1] 表示拿到 text
        const label = children[1];
        if (!label) return;

        if (value) {
          label.attr('fontWeight', 800);
        } else {
          label.attr('fontWeight', 500);
        }
      }
    } catch (error) {
      console.error('Error in setState:', error);
    }
  },

  getAnchorPoints() {
    return [
      [0, 0],
      [1, 0],
    ];
  },
});

G6.registerEdge('dice-er-edge', {
  draw: function draw(cfg: any, group: any) {
    const edge = group.cfg.item;
    const style = cfg.style;
    const sourceNode = edge.getSource().getModel();
    const targetNode = edge.getTarget().getModel();

    const sourceIndex = sourceNode.attrs.findIndex(
      (e: any) => e.key === cfg.sourceAnchor
    );

    const sourceStartIndex = sourceNode.startIndex || 0;

    let sourceY = itemHeight / 2 + lineWidth / 2;

    if (sourceIndex > sourceStartIndex - 1) {
      sourceY =
        itemHeight + (sourceIndex - sourceStartIndex + 0.5) * itemHeight;
    }

    const targetIndex = targetNode.attrs.findIndex(
      (e: any) => e.key === cfg.targetAnchor
    );

    const targetStartIndex = targetNode.startIndex || 0;

    let targetY = itemHeight / 2 + lineWidth / 2;

    if (targetIndex > targetStartIndex - 1) {
      targetY =
        (targetIndex - targetStartIndex + 0.5) * itemHeight + itemHeight;
    }

    const startPoint = {
      ...cfg.startPoint,
    };
    const endPoint = {
      ...cfg.endPoint,
    };

    startPoint.y = startPoint.y + sourceY;
    endPoint.y = endPoint.y + targetY;

    let shape; // 就是那条线

    if (sourceNode.id !== targetNode.id) {
      shape = group.addShape('path', {
        attrs: {
          stroke: style.stroke,
          lineWidth: style.lineWidth,
          path: [
            ['M', startPoint.x, startPoint.y],
            [
              'C',
              endPoint.x / 3 + (2 / 3) * startPoint.x,
              startPoint.y,
              endPoint.x / 3 + (2 / 3) * startPoint.x,
              endPoint.y,
              endPoint.x,
              endPoint.y,
            ],
          ],
          endArrow: true,
        },
        name: 'path-shape',
      });
    }

    return shape;
  },

  /**
   * 设置状态，主要用于高亮
   * @param name 状态
   * @param value true | false
   * @param item 要改变状态的边
   */
  setState(name, value, item: any) {
    try {
      if (!item) return;
      const shape = item.get('keyShape');
      if (!shape) return;

      // 字段连线高亮或表连线高亮
      if (name && name.startsWith('highlight')) {
        const highlightColor = name.split('-')[1];
        if (value) {
          shape.attr('stroke', highlightColor);
          shape.attr('lineWidth', 3);
        } else {
          shape.attr('stroke', '#6C6B6B');
          shape.attr('lineWidth', 2);
        }
      }
    } catch (error) {
      console.error('Error in edge setState:', error);
    }
  },
});
