/**
 * 运行时配置：我要调的后端地址（apiBaseUrl）与我自己对外的地址（baseUrl）。
 *
 * <h2>为什么需要它 —— 打包态改地址不该重新构建镜像</h2>
 *
 * <p>`import.meta.env.VITE_*` 是 Vite 的**构建期**变量，会被字面量烘焙进产物：
 * 镜像一旦构建完成，再改环境变量就没用了，只能重新 build。而「前端和后端不一定在
 * 同一台服务器上」这件事恰恰是部署期才知道的，于是运维被逼着为改一个地址重建镜像。
 *
 * <p>所以这里多一条通路：容器启动时由 nginx entrypoint 脚本用环境变量渲染
 * `/config.json`（见 ui/Dockerfile、ui/config.json.template 与
 * ui/docker-entrypoint.d/25-app-config.sh），前端启动时 fetch 它。
 *
 * <p>⚠️ 同名不等于同一条通路：`VITE_API_BASE_URL` 在构建期是 Vite 的编译期变量，
 * 在容器启动期只是一个普通环境变量。容器里改的是后者，**不需要**重新构建。
 *
 * <h2>生效优先级（唯一一条规则）</h2>
 *
 * <p>`config.json` 里非空的字段 > 构建期 `VITE_*` > 内置默认。
 * config.json 优先，正是为了让容器启动时的环境变量能盖过镜像里构建时烘焙的值。
 *
 * <p>三份副本（dw-org / dw-model / dw-lineage）必须**逐字一致**。抽进 packages/engine
 * 共享留到下一版，与 ProductEmbed 抽包是同一件事。
 */
export interface AppConfig {
  /** 我要调的后端地址。空 = 同源相对路径（由 nginx / vite 代理），保持既有默认行为。 */
  apiBaseUrl: string;
  /** 我自己的对外地址。空 = 不声明，由后端/宿主告知。 */
  baseUrl: string;
}

let cfg: AppConfig = { apiBaseUrl: '', baseUrl: '' };

function str(v: unknown): string {
  return typeof v === 'string' ? v.trim() : '';
}

/**
 * 读取运行时配置。
 *
 * <p>**必须在 `createApp()` 之前 await**：地址是模块顶层求值的，晚了就会有一批请求
 * 带着旧基址发出去（见各模块 api/client.ts 里 configureApiBase 的说明）。
 *
 * <p>读不到不算错误：dev 下没有容器渲染这一步，public/config.json 里是空的，
 * 于是回落到环境变量 —— 与改动前的行为一致。
 */
export async function loadAppConfig(): Promise<void> {
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    if (res.ok) {
      const j = (await res.json()) as Partial<AppConfig>;
      cfg = { apiBaseUrl: str(j.apiBaseUrl), baseUrl: str(j.baseUrl) };
    }
  } catch {
    /* 读不到就走下面的回落 */
  }
  cfg.apiBaseUrl ||= str(import.meta.env.VITE_API_BASE_URL);
  cfg.baseUrl ||= str(import.meta.env.VITE_BASE_URL);
}

/** 当前配置。挂载前调用 loadAppConfig() 之后才有非空值。 */
export function appConfig(): AppConfig {
  return cfg;
}
