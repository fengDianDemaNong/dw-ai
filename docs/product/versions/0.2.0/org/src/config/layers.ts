export function layerHref(layer: string) {
  return `/app/model/${encodeURIComponent(layer.toLowerCase())}`;
}

export function parseLayerParam(raw: unknown) {
  return decodeURIComponent(String(raw ?? '')).toUpperCase();
}

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

export function generateHref(from: string, to: string) {
  if (from === 'ODS' && to === 'DWD') return '/app/model/ods-dwd';
  if (from === 'DWD' && to === 'DWS') return '/app/model/dwd-dws';
  return null;
}

export function layerAiHref(layer: string, tableId?: string) {
  const base = `${layerHref(layer)}/ai`;
  return tableId ? `${base}?table=${encodeURIComponent(tableId)}` : base;
}

export function layerVersionsHref(layer: string, domain: string, tableId: string) {
  return `${layerHref(layer)}/${encodeURIComponent(domain)}/${encodeURIComponent(tableId)}/versions`;
}
