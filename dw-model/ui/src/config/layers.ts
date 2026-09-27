import { MODEL_HOME } from './paths';

export const TABLE_STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  deprecated: '已下线',
};

export function layerHref(layer: string) {
  return `${MODEL_HOME}/${encodeURIComponent(layer.toLowerCase())}`;
}

export function parseLayerParam(raw: unknown) {
  return decodeURIComponent(String(raw ?? '')).toUpperCase();
}

/**
 * 产品**内置认识**的层码：`layerIcon` / `layerTone` 给它们配了图标与配色，
 * 层页面（`pages/model/dwd-overview.vue` 那套）也按它们渲染。
 *
 * <p>为什么是这六个而不是 `seedStdLayers` 灌的那四个（ODS/DWD/DWS/ADS）：那四个只是
 * 「勾了初始化标准规范」的项目开局就有的层，`DIM`/`STG` 同样是一等公民（建维表、
 * 贴源缓冲都用得上），产品认得它们。
 *
 * <p><b>这个集合不参与「报给组织平台的菜单候选」</b>：分层是**运行期逐项目**的事实
 * （项目建好后还能自由增删改），而 org 壳的侧栏配置是全局的、多项目共用一份 ——
 * 静态清单表达不了它。见 `navData.ts` 里 `MODELING_CANDIDATES` 删除处的说明。
 */
export function layerIcon(layer: string) {
  const map: Record<string, string> = {
    ODS: 'InboxOutlined',
    DWD: 'TableOutlined',
    DWS: 'ClusterOutlined',
    ADS: 'AppstoreOutlined',
    DIM: 'ApartmentOutlined',
    STG: 'SwapOutlined',
  };
  return map[layer.toUpperCase()] ?? 'DatabaseOutlined';
}

export function layerTone(layer: string) {
  const map: Record<string, string> = {
    ODS: 'ods',
    DWD: 'dwd',
    DWS: 'dws',
    ADS: 'ads',
    DIM: 'dim',
    STG: 'stg',
  };
  return map[layer.toUpperCase()] ?? 'ods';
}

export function layerAiHref(layer: string, tableId?: string) {
  const base = `${layerHref(layer)}/ai`;
  return tableId ? `${base}?table=${encodeURIComponent(tableId)}` : base;
}

export function layerVersionsHref(layer: string, domain: string, tableId: string) {
  return `${layerHref(layer)}/${encodeURIComponent(domain)}/${encodeURIComponent(tableId)}/versions`;
}

export function generateHref(from: string, to: string) {
  if (from === 'ODS' && to === 'DWD') return `${MODEL_HOME}/ods-dwd`;
  if (from === 'DWD' && to === 'DWS') return `${MODEL_HOME}/dwd-dws`;
  return null;
}
