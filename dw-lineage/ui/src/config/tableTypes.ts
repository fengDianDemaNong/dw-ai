/**
 * 数仓表类型。
 *
 * 取值与参照项目 mdm 的 `tableTypeEnums` 保持一致，便于两边对照。
 *
 * 注意这与解析器里的 `CreateTable.tableType`（HIVE / ICEBERG 之类）不是一回事 ——
 * 那是存储格式，这是业务上的表建模方式。所以导入建表语句时不会自动推断这一项，
 * 需要用户自己选。
 */
export const tableTypeEnums: Record<string, string> = {
  FULL: '全量表',
  INCRE: '增量表',
  SNAPSHOT_FULL: '快照表(全量)',
  SNAPSHOT_INCRE: '快照表(增量)',
  ZIPPER: '拉链表',
  ARCH: '归档表',
};

export const tableTypeOptions = Object.entries(tableTypeEnums).map(([value, label]) => ({
  value,
  label,
}));

/** 未知取值原样显示，不要吞掉 —— 那通常意味着数据是从别处导入的。 */
export function tableTypeLabel(value?: string): string {
  if (!value) {
    return '';
  }
  return tableTypeEnums[value] ?? value;
}
