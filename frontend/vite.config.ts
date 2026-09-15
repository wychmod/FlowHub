import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// 端口约定：后端 8080，前端 5174
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    // 端口被占用时直接报错，避免静默漂移到 5175
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
