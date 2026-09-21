/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_PUBLIC_PATH: string;
  readonly VITE_ENV: string;
  /** 'true' 时概览页在接口失败后回落到本地假数据。只在开发模式下被读取 */
  readonly VITE_STATS_MOCK?: string;
  readonly VITE_RUN_MODE?: 'standalone' | 'standard' | 'multi';
  readonly VITE_ORG_ORIGIN?: string;
  readonly VITE_WAREHOUSE_ORIGIN?: string;
  readonly VITE_DEV_PORT?: string;
  // 更多环境变量...
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
