import {
  itemHeight,
  maxLevel,
  nodeWidth,
} from '../components/LineageGraph/registerShape';
import { LineageData, LineageItem } from '../types';
import { catalogState } from '../stores/catalog';
// 创建表名到最高层级的映射
const targetTables: Set<string> = new Set();
// 自定义数据转换
export const transformData = (data: LineageData[], shouldClear: boolean = false) => {
  if (shouldClear) {
    targetTables.clear();
  }
  const nodes: any[] = [];
  // 用 map 实现对象去重
  const edgeMap: Map<string, any> = new Map();
  // 记录字段final
  const tableFieldIsFinal: Set<string> = new Set();
  // 用 set 实现表名去重
  const tableFields: Set<any> = new Set();
  data.forEach((item: LineageData) => {
    const targetField = item.targetField;
    if (!targetField) return;
    const tableField = handleTableField(targetField);
    const targetTableName = getTableFieldName(targetField.fieldName).tableName;
    if (!targetTables.has(targetTableName)) {
      targetTables.add(targetTableName);
    }
    if (targetField?.final) {
      tableFieldIsFinal.add(tableField);
    }
    tableFields.add(tableField);
    if (item.refFields) {
      createEdge(edgeMap, tableFields, tableField, item.refFields,tableFieldIsFinal);
    }
  });
  createNode(nodes, tableFields,tableFieldIsFinal);
  const edges = Array.from(edgeMap.values());

  return {
    nodes,
    edges,
  };
};

/**
 * 创建 Edge 即连线即字段之间的连线
 */
const createEdge = (
  edgeMap: Map<string, any>,
  tableFields: Set<any>,
  tableField: string,
  refFields: LineageItem[],
  tableFieldIsFinal: Set<string>
) => {
  const target = getTableFieldName(tableField);
  const targetName = target.tableName;
  const targetAnchor = target.tableField;
  refFields.forEach((ref: any) => {
    const tableField = handleTableField(ref);
    tableFields.add(tableField);
    if (ref?.final) {
      tableFieldIsFinal.add(tableField);
    }
    const source = getTableFieldName(tableField);
    const sourceName = source.tableName;
    const sourceAnchor = source.tableField;
    // 不能自连，防止绘制失败
    if (targetName === sourceName) {
      return;
    }

    const edge: any = {};
    edge.source = sourceName;
    edge.sourceAnchor = sourceAnchor;
    edge.target = targetName;
    edge.targetAnchor = targetAnchor;
    edge.label = ref.label;
    let key = sourceName + sourceAnchor + '-' + targetName + targetAnchor;
    edgeMap.set(key, edge);
  });
};

/**
 * 拼接表名+字段，逻辑可参考文档
 */
const handleTableField = (item: any) => {
  const fieldName = item.fieldName;
  let tableField = '';
  // if (item.final){
  if (item.final && !targetTables.has(getTableFieldName(fieldName).tableName)) {
    tableField = fieldName;
  } else {
    tableField = `${item.level}-${item.index}:${fieldName}`;
  }
  return tableField;
};

/**
 * 拆分字符串获取表名称，字段名称
 */
const getTableFieldName = (item: string) => {
  const names: string[] = item.split(':');
  let tableName = '';
  let tableField = '';
  let originalTableName = ''; // 存储原始表名，用于显示

  if (names.length === 1) {
    const array = names[0].split('.');
    tableField = array.pop() ?? '';
    tableName = array.join('.');
    originalTableName = array.join('.'); // 原始表名就是 array[1]
  } else {
    const array = names[1].split('.');
    tableField = array.pop() ?? '';
    tableName = array.join('.') + '_' + names[0];
    originalTableName = array.join('.'); // 原始表名是 array[1]
  }

  return { tableName, tableField, originalTableName };
};

/**
 * 节点 key 的层级后缀，形如 `dws.dws_user_order_1d_2-0` 里的 `_2-0`。
 *
 * 必须整体匹配 `_数字-数字` 才算后缀：以前的写法是「砍掉最后一个下划线之后的内容」，
 * 而表名本身就带下划线 —— 没有后缀的节点会被误砍，
 * `ods.ods_order` 显示成 `ods.ods`。
 */
const LEVEL_SUFFIX = /_\d+-\d+$/;

/** 从节点 key 还原真实表名。没有层级后缀时原样返回。 */
export const stripLevelSuffix = (key: string): string =>
  typeof key === 'string' ? key.replace(LEVEL_SUFFIX, '') : key;

/**
 * 展示用的表名 / 字段名：属于默认数据目录的，把目录那一段隐去。
 *
 * ```
 * displayName('default.ods.orders',   'default') === 'ods.orders'
 * displayName('hive_prod.ods.orders', 'default') === 'hive_prod.ods.orders'
 * ```
 *
 * 库里的全名恒为三段 `目录.库.表`，但绝大多数表都在默认目录下，
 * 每个名字都顶着同样的前缀纯属噪音；非默认目录才是需要看清的场合，保持全名。
 *
 * <p><b>只用于渲染。</b>传给后端的筛选值、表格 row-key、页面跳转参数
 * 一律要用完整全名 —— 用这里的短名去查，默认目录下的表会全部查不到。
 *
 * <p>只切首段、且必须与默认目录名完全相等：库名恰好也叫 `default` 时
 * （`ods.default.orders`）不能误伤。`defaultCatalog` 为空表示不处理，
 * 用于默认目录还没拉到的那一小段时间。
 */
export const displayName = (fullName: string, defaultCatalog: string): string => {
  if (typeof fullName !== 'string' || !defaultCatalog) return fullName;
  const prefix = `${defaultCatalog}.`;
  return fullName.startsWith(prefix) ? fullName.slice(prefix.length) : fullName;
};

/**
 * 获取表层级及order
 */
const getTableLevelAndOrder = (tableField: string) => {
  let level = maxLevel;
  let order = 0;
  const endIndex = tableField.lastIndexOf('-');
  if (endIndex !== -1) {
    const startIndex = tableField.lastIndexOf('_');
    level = Number(tableField.slice(startIndex + 1, endIndex));
    order = Number(tableField.slice(endIndex + 1, tableField.length));
  }
  return { level, order };
};

/**
 * 创建 Node 即节点即表
 */
const createNode = (nodes: any[], tableFields: Set<any>,tableFieldIsFinal: Set<any>) => {
  const tables: Map<string, string[]> = new Map();
  tableFields.forEach((item: any) => {
    const table = getTableFieldName(item);
    const tableName = table.tableName;

    var fieldColor='#000';
    if (tableFieldIsFinal.has(item)) {
      fieldColor ='#1890FF';
    }
    const tableField = table.tableField + ':'+fieldColor;
    if (!tables.has(tableName)) {
      tables.set(tableName, [tableField]);
    } else {
      const attrs: any = tables.get(tableName);
      if (!attrs?.includes(tableField)) {
        attrs?.push(tableField);
        tables.set(tableName, attrs);
      }
    }
  });
  tables.forEach((value: string[], key: any, map) => {
    const attrs: any[] = [];
    // 确保 value 是数组并且有元素
    if (Array.isArray(value)) {
      value.forEach((attr: any) => {
        if (attr) {// 确保 attr 不为空
          var [filed,color ] =attr.split(':');
          attrs.push({
            nodeId: key,
            key: filed,
            type: '',
            originalColor:color,
            color:color,
          });
        }
      });
    }

    // 从 key 中还原真实表名（去掉 `_层级-序号` 后缀，没有后缀就原样用）
    const originalTableName = stripLevelSuffix(key);

    const { level, order } = getTableLevelAndOrder(key);
    const height = itemHeight * (Math.max(attrs.length, 1) + 1); // 确保至少有一行高度
    const obj: any = {
      id: key,
      key: key,
      // label 只管显示：默认数据目录那一段隐去。id / key 仍是完整名字，
      // 布局、连线、点击回调全靠它们，动了就对不上了
      label: displayName(originalTableName, catalogState.defaultCatalog) || key,
      x: 100,
      y: 100,
      level: level,
      order: order,
      attrs: attrs || [], // 确保 attrs 始终是数组
      size: [nodeWidth, height],
    };
    nodes.push(obj);
  });
};

/**
 * 处理表级数据，即当字段级血缘关系为 false 时
 */
export const collapseData = (data: any) => {
  const nodes: any[] = [];
  const edgeMap: Map<string, any> = new Map();
  const tableFields: Set<any> = new Set();

  data.forEach((item: any) => {
    const targetField = item.targetField;
    const tableField = handleTableField(targetField);
    tableFields.add(tableField);

    if (item.refFields) {
      createCollapsedEdge(edgeMap, tableFields, tableField, item.refFields);
    }
  });

  const edges = Array.from(edgeMap.values());
  createCollapsedNode(nodes, tableFields);

  return {
    nodes,
    edges,
  };
};

const createCollapsedEdge = (
  edgeMap: Map<string, any>,
  tableFields: Set<any>,
  tableField: string,
  refFields: any[]
) => {
  const target = getTableFieldName(tableField);
  const targetName = target.tableName;
  refFields.forEach((ref: any) => {
    const tableField = handleTableField(ref);
    tableFields.add(tableField);
    const source = getTableFieldName(tableField);
    const sourceName = source.tableName;
    // 不能自连，防止绘制失败
    if (targetName === sourceName) {
      return;
    }

    const edge: any = {};
    edge.source = sourceName;
    edge.sourceAnchor = sourceName;
    edge.target = targetName;
    edge.targetAnchor = targetName;
    edge.label = ref.label;
    let key = sourceName + '-' + targetName;
    edgeMap.set(key, edge);
  });
};

const createCollapsedNode = (nodes: any[], tableFields: Set<any>) => {
  const tables: Set<string> = new Set();
  tableFields.forEach((item: any) => {
    const table = getTableFieldName(item);
    const tableName = table.tableName;
    tables.add(tableName);
  });

  tables.forEach((key: string, value: any) => {
    // 从 key 中还原真实表名（去掉 `_层级-序号` 后缀，没有后缀就原样用）
    const originalTableName = stripLevelSuffix(key);

    const { level, order } = getTableLevelAndOrder(key);
    const obj: any = {
      id: key,
      key: key,
      // label 只管显示：默认数据目录那一段隐去。id / key 仍是完整名字，
      // 布局、连线、点击回调全靠它们，动了就对不上了
      label: displayName(originalTableName, catalogState.defaultCatalog) || key,
      x: 100,
      y: 100,
      level: level,
      order: order,
      attrs: [], // 确保是空数组而不是 undefined
      size: [nodeWidth, itemHeight],
    };
    nodes.push(obj);
  });
};

/**
 * 获取选中 label 的所有左关联边
 * @param edges node 的所有 edges
 * @param model node 的 model
 * @param sourceAnchor 选中的 label
 * @param leftActiveEdges 左关联边集合
 */
export const getLeftRelation = (
  edges: any[],
  model: any,
  sourceAnchor: any,
  leftActiveEdges: any[]
) => {
  const source = model['id']; // 当前节点
  edges
    .filter((edge: any) => !leftActiveEdges.includes(edge))
    .forEach((edge: any) => {
      if (
        edge.getModel()['target'] === source &&
        edge.getModel()['targetAnchor'] === sourceAnchor
      ) {
        leftActiveEdges.push(edge);

        const currentNode = edge.getSource();
        const currentModel = currentNode.getModel();
        const currentEdges = currentNode.getInEdges();
        const currentSourceAnchor = edge.getModel()['sourceAnchor'];
        getLeftRelation(
          currentEdges,
          currentModel,
          currentSourceAnchor,
          leftActiveEdges
        );
      }
    });
};

/**
 * 获取选中 label 的所有右关联边
 * @param edges node 的所有 edges
 * @param model node 的 model
 * @param sourceAnchor 选中的 label
 * @param rightActiveEdges 右关联边集合
 */
export const getRightRelation = (
  edges: any[],
  model: any,
  sourceAnchor: any,
  rightActiveEdges: any[]
) => {
  const source = model['id']; // 当前节点
  edges
    .filter((edge: any) => !rightActiveEdges.includes(edge))
    .forEach((edge: any) => {
      if (
        edge.getModel()['source'] === source &&
        edge.getModel()['sourceAnchor'] === sourceAnchor
      ) {
        rightActiveEdges.push(edge);

        const currentNode = edge.getTarget();
        const currentModel = currentNode.getModel();
        const currentEdges = currentNode.getOutEdges();
        const currentTargetAnchor = edge.getModel()['targetAnchor'];
        getRightRelation(
          currentEdges,
          currentModel,
          currentTargetAnchor,
          rightActiveEdges
        );
      }
    });
};
