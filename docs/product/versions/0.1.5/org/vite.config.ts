import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@dw-ai/engine': fileURLToPath(new URL('./packages/engine/src/index.ts', import.meta.url)),
    },
  },
  optimizeDeps: {
    exclude: ['@dw-ai/engine'],
  },
  server: {
    port: 4224,
    host: '127.0.0.1',
    strictPort: true,
    headers: {
      'Cache-Control': 'no-store',
    },
    watch: {
      ignored: ['**/node_modules/**'],
    },
  },
});
