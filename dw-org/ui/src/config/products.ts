/**
 * 产品码与中文名。
 *
 * <p><b>这是「产品码」那一套词汇</b>（`service_registry.product` / `nav_items.product`），
 * 与服务端 `ProductCodes.KNOWN` 对应。它与租户许可的模块名（`config/iam.ts` 的
 * `MODULE_OPTIONS`）<b>不是同一套</b>：这里只列有独立进程、能登记页面地址的产品，
 * 比许可表少了 `materialize` / `dev`（那两个没有独立站点）。
 *
 * <p>两边逐字相同的四个码才能被菜单的许可过滤命中 —— 服务端 `ProductCodes` 有同样的
 * 白名单和一条守卫测试钉住这层包含关系；前端这份只负责显示，不做过滤。
 */
export const PRODUCT_OPTIONS: { value: string; label: string }[] = [
  { value: 'warehouse', label: '数仓建模' },
  { value: 'metadata', label: '元数据 / 血缘' },
  { value: 'quality', label: '数据质量' },
  { value: 'serve', label: '数据服务' },
];

export function productLabel(code: string): string {
  return PRODUCT_OPTIONS.find((p) => p.value === code)?.label ?? code;
}

/**
 * 有子端接收端、能嵌进 org 壳的产品（子端见各产品的 `config/embed.ts`）。
 *
 * <p>其余产品的前端没有接收端：嵌进壳里只会是一个打不开的空页面，所以侧栏给
 * 整页跳转链接（见 `config/sysNav.ts` 的 `toNavItem`），成员的落地页也不选它们。
 * 等它们补上子端，把产品码加进这个集合即可 —— 服务端不用动。
 */
const EMBEDDABLE = new Set(['metadata', 'warehouse']);

export function isEmbeddable(product: string): boolean {
  return EMBEDDABLE.has(product);
}
