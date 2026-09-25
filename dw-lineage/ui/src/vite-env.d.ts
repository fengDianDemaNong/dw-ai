/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_PUBLIC_PATH: string;
  readonly VITE_ENV: string;
  /** 'true' 时概览页在接口失败后回落到本地假数据。只在开发模式下被读取 */
  readonly VITE_STATS_MOCK?: string;
  readonly VITE_RUN_MODE?: 'standalone' | 'standard' | 'multi';
  readonly VITE_API_BASE_URL?: string;
  /** 本控制台自己的对外地址，见 config/appConfig.ts */
  readonly VITE_BASE_URL?: string;
  readonly VITE_DEV_PORT?: string;
  // 更多环境变量...
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
