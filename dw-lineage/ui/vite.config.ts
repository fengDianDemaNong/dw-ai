import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * @antv/g-base、g-canvas、g-svg 的 esm 产物里混入了 CommonJS 写法：
 *   var pkg = require('../package.json');
 * 这些文件同时含有 import/export，esbuild 与 rollup 都按 ESM 处理，
 * 于是 require 被原样保留到浏览器产物中，运行时抛
 * "Uncaught ReferenceError: require is not defined"，整个前端白屏。
 *
 * 上游只用它取 pkg.version（且取完就丢弃），因此直接替换成对象字面量。
 * 构建（rollup）与开发预构建（esbuild）两条链路都要处理。
 */
const ANTV_PKG_REQUIRE = /require\(\s*['"]\.\.\/package\.json['"]\s*\)/g;

function stripAntvPkgRequire(code: string) {
  return code.replace(ANTV_PKG_REQUIRE, '{ version: "0.0.0" }');
}

function antvPkgRequireShim(): Plugin {
  return {
    name: 'antv-pkg-require-shim',
    enforce: 'pre',
    // 开发模式：esbuild 依赖预构建
    config() {
      return {
        optimizeDeps: {
          esbuildOptions: {
            plugins: [
              {
                name: 'antv-pkg-require-shim',
                setup(build: any) {
                  build.onLoad(
                    { filter: /[\\/]@antv[\\/]g-(base|canvas|svg)[\\/]esm[\\/].*\.js$/ },
                    (args: { path: string }) => ({
                      contents: stripAntvPkgRequire(readFileSync(args.path, 'utf-8')),
                      loader: 'js' as const,
                    })
                  );
                },
              },
            ],
          },
        },
      };
    },
    // 生产构建：rollup
    transform(code, id) {
      if (!id.includes('@antv/') || !ANTV_PKG_REQUIRE.test(code)) return null;
      ANTV_PKG_REQUIRE.lastIndex = 0;
      return { code: stripAntvPkgRequire(code), map: null };
    },
  };
}

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd());
  const { VITE_PUBLIC_PATH } = env;
  /**
   * dev 代理目标。三个 UI 用同一段取值逻辑（dw-org / dw-model 的 vite.config.ts 同款）：
   *
   *   1. VITE_DEV_PROXY_TARGET   —— 显式指定，优先级最高（另起一套端口做验证时用）
   *   2. VITE_API_BASE_URL       —— 填的是绝对地址时，它就是后端地址，直接拿来做代理目标
   *   3. 本模块后端默认端口 18082
   *
   * <p>第 3 条以前是 8080 —— 而 8080 从来不是本服务的端口（18082 才是），于是
   * 不带 VITE_DEV_PROXY_TARGET 起 dev 时，前端会把 /api 代理到空无一物的 8080，
   * 表现为「接口 401/404 → 静默回落登录页」。这是那次故障的直接根因。
   */
  const apiBase = process.env.VITE_API_BASE_URL || env.VITE_API_BASE_URL || '';
  const proxyTarget =
    process.env.VITE_DEV_PROXY_TARGET ||
    env.VITE_DEV_PROXY_TARGET ||
    (/^https?:\/\//.test(apiBase) ? apiBase : '') ||
    'http://127.0.0.1:18082';

  return {
    base: VITE_PUBLIC_PATH || '/',
    plugins: [antvPkgRequireShim(), vue()],
    resolve: {
      // 数组形式（而不是对象）只为了给 engine 那条写正则：对象形式的 key 只能是字符串，
      // 而字符串在 vite 里是**前缀**匹配 —— 写 '@dw-ai/engine' 会把子路径导入
      // '@dw-ai/engine/iam' 一起改写成 '<...>/src/index.ts/iam' 这个不存在的路径。
      // 加 `^$` 锚定后子路径不再命中这条，交给包自己的 exports 解析
      // （见 packages/engine/package.json 的 "./iam"）。
      //
      // engine 是 npm workspace 里的源码包（exports 直指 src/index.ts，无需构建），
      // 顶层 workspace 的 node_modules/@dw-ai/engine 是指向 packages/engine 的软链。
      alias: [
        {
          find: /^@dw-ai\/engine$/,
          replacement: fileURLToPath(
            new URL('../../packages/engine/src/index.ts', import.meta.url)
          ),
        },
        { find: 'ant-design-vue', replacement: 'ant-design-vue/es' },
        // 顶替 g6-pc 引用的一个上游并不存在的模块，详见该 shim 文件内的说明
        {
          find: '@antv/algorithm/lib/asyncIndex',
          replacement: fileURLToPath(
            new URL('./src/shims/antv-algorithm-async.ts', import.meta.url)
          ),
        },
      ],
    },
    optimizeDeps: {
      // engine 是 TS 源码，交给 vite 按源码走，不进 esbuild 预构建
      exclude: ['@dw-ai/engine'],
      // 但它顶层 import 了 xlsx。被 exclude 的包，它带进来的依赖会漏出 vite
      // 启动时的依赖扫描，直到「第一次访问用到它的页面」才被 on-demand 发现 ——
      // 那时 vite 现场补一轮预构建并整页 reload，表现为「每个产品第一次点它的
      // 页面要等几秒，之后同产品内就正常」。显式 include 把 xlsx 拉回启动那一批。
      include: ['xlsx'],
    },
    css: {
      preprocessorOptions: {
        less: {
          javascriptEnabled: true,
        },
      },
    },
    server: {
      port: Number(process.env.VITE_DEV_PORT || env.VITE_DEV_PORT) || 5173,
      // 端口被占时**报错退出**，不顺延到下一个。Vite 默认是「占了就换一个」，
      // 只在控制台印一行 `Port 5173 is in use, trying another one...` 就继续 ——
      // 于是「5173 起了没有」这个问题，答案取决于当时谁先占了它。
      // 端口可经 VITE_DEV_PORT 指定（见 package.json 的 dev:multi），本行不影响。
      strictPort: true,
      host: true,
      // 前端统一走相对路径 /api，由此处代理到后端，避免跨域与硬编码地址。
      //
      // 没有 /internal —— 那是服务间接口（受 module token 门禁，调用方是组织），
      // 浏览器不该直打。此前挂在这里，只是为了给一条已删除的前端兜底逻辑让路。
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      // monaco 与 g6 体积较大，单独分包避免主 chunk 过大
      rollupOptions: {
        output: {
          manualChunks: {
            monaco: ['monaco-editor'],
            g6: ['@antv/g6'],
          },
        },
      },
      chunkSizeWarningLimit: 1500,
    },
  };
});
