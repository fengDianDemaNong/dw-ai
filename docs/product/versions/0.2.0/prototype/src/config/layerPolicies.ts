import type { LayerRule } from '../types';

export {
  MASKING_OPTIONS,
  NULL_OPTIONS,
  maskingLabel,
  nullLabel,
  defaultLayerPolicy,
  hydrateLayerRule,
  specLayerHref,
} from '@dw-ai/engine';
export type { LayerPolicy } from '@dw-ai/engine';

export type LayerTemplate = {
  id: string;
  name: string;
  summary: string;
  layers: Omit<LayerRule, 'projectId'>[];
};

export const LAYER_TEMPLATES: LayerTemplate[] = [
  {
    id: 'std-4',
    name: '通用四层',
    summary: 'ODS / DWD / DWS / ADS，带命名、保留和对外策略。导入后仍可改。',
    layers: [
      {
        layer: 'ODS',
        naming: 'ods_[源系统]_[表名]_[增量标记]',
        retention: '3-7天',
        serve: 'forbid',
        note: '原始数据，镜像同步',
      },
      {
        layer: 'DWD',
        naming: 'dwd_[主题域]_[业务过程]_[粒度]_[周期]',
        retention: '永久',
        serve: 'forbid',
        note: '清洗后明细，维度退化',
      },
      {
        layer: 'DWS',
        naming: 'dws_[主题域]_[业务过程]_[统计周期]',
        retention: '永久',
        serve: 'approval',
        note: '轻度汇总，面向分析',
      },
      {
        layer: 'ADS',
        naming: 'ads_[应用]_[业务场景]',
        retention: '按需',
        serve: 'allow',
        note: '应用层，直接服务',
      },
    ],
  },
];
