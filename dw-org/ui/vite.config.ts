import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

const pkg = JSON.parse(readFileSync(fileURLToPath(new URL('./package.json', import.meta.url)), 'utf8')) as {
  version: string;
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd());
  /**
   * dev 代理目标。三个 UI 用同一段取值逻辑（dw-model / dw-lineage 的 vite.config.ts 同款）：
   *
   *   1. VITE_DEV_PROXY_TARGET   —— 显式指定，优先级最高
   *   2. VITE_API_BASE_URL       —— 填的是绝对地址时，它就是后端地址，直接拿来做代理目标
   *   3. 本模块后端默认端口 18080
   *
   * <p>第 1 条可覆盖是为了「另起一套端口做验证」：验证实例跑在 18090，
   * 而写死 18080 时只能去改这个文件（改了就容易顺手提交）。
   */
  const apiBase = process.env.VITE_API_BASE_URL || env.VITE_API_BASE_URL || '';
  const proxyTarget =
    process.env.VITE_DEV_PROXY_TARGET ||
    env.VITE_DEV_PROXY_TARGET ||
    (/^https?:\/\//.test(apiBase) ? apiBase : '') ||
    'http://127.0.0.1:18080';

  return {
    define: {
      __APP_VERSION__: JSON.stringify(pkg.version),
    },
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@dw-ai/engine': fileURLToPath(
          new URL('../../packages/engine/src/index.ts', import.meta.url)
        ),
      },
    },
    optimizeDeps: {
      exclude: ['@dw-ai/engine'],
      // engine 顶层 import 了 xlsx。被 exclude 的包，它带进来的依赖会漏出 vite
      // 启动时的依赖扫描，直到「第一次访问用到它的页面」才被 on-demand 发现 ——
      // 那时 vite 现场补一轮预构建并整页 reload，表现为「每个产品第一次点它的
      // 页面要等几秒，之后同产品内就正常」。显式 include 把 xlsx 拉回启动那一批。
      include: ['xlsx'],
    },
    server: {
      port: 5171,
      // 端口被占时**报错退出**，不顺延到下一个。Vite 默认是「占了就换一个」，
      // 只在控制台印一行 `Port 5171 is in use, trying another one...` 就继续 ——
      // 于是按文档打开 5171 的人看到的是「没响应」，而服务其实起在别的端口上。
      strictPort: true,
      host: true,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/internal': { target: proxyTarget, changeOrigin: true },
      },
    },
  };
});
