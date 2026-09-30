import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue(),
  ],
  // 预构建 Element Plus 及其日期依赖，避免浏览器逐个加载 ESM 文件时出现 CJS 默认导出错误。
  optimizeDeps: { include: ['element-plus', 'dayjs'] },
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  // 测试环境关闭 CSS 处理，专注验证组件逻辑。
  test: {
    environment: 'jsdom',
    css: false,
  },
  // 不拆分 Element Plus，避免旧版构建器产生错误的 chunk 边界。
})
