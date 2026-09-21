/// <reference types="vite/client" />

declare const __APP_VERSION__: string;

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_RULES_BASE?: string;
  readonly VITE_DEPLOY_MODE?: 'standalone' | 'standard' | 'multi';
  readonly VITE_RUN_MODE?: 'standalone' | 'standard' | 'multi';
  readonly VITE_PRODUCT?: 'org' | 'warehouse' | 'suite';
  readonly VITE_ORG_ORIGIN?: string;
  readonly VITE_WAREHOUSE_ORIGIN?: string;
  readonly VITE_LINEAGE_ORIGIN?: string;
  readonly VITE_CASDOOR_ENDPOINT?: string;
  readonly VITE_CASDOOR_CLIENT_ID?: string;
  readonly VITE_CASDOOR_ORG?: string;
  readonly VITE_CASDOOR_APP?: string;
  readonly VITE_CASDOOR_REDIRECT_URI?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<object, object, unknown>;
  export default component;
}

