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
  const proxyTarget =
    process.env.VITE_DEV_PROXY_TARGET || env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080';

  return {
    base: VITE_PUBLIC_PATH || '/',
    plugins: [antvPkgRequireShim(), vue()],
    resolve: {
      alias: {
        'ant-design-vue': 'ant-design-vue/es',
        // 顶替 g6-pc 引用的一个上游并不存在的模块，详见该 shim 文件内的说明
        '@antv/algorithm/lib/asyncIndex': fileURLToPath(
          new URL('./src/shims/antv-algorithm-async.ts', import.meta.url)
        ),
      },
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
      host: true,
      // 前端统一走相对路径 /api，由此处代理到后端，避免跨域与硬编码地址
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
        '/internal': {
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
