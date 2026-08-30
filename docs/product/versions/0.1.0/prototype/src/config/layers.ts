export function layerHref(layer: string) {
  return `/w/model/${encodeURIComponent(layer.toLowerCase())}`;
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
  if (from === 'ODS' && to === 'DWD') return '/w/model/ods-dwd';
  if (from === 'DWD' && to === 'DWS') return '/w/model/dwd-dws';
  return null;
}
