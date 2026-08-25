import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * 前端单元测试配置（Vitest）。
 * 对 API/纯逻辑测试使用 node 环境即可，无需 DOM；渲染类测试后续可切到 jsdom。
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});