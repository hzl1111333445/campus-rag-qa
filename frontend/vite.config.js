import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * 这里最关键的一行是 proxy：开发时前端跑在 5173，后端跑在 8081（IDEA）或 8090（命令行验证），
 * 浏览器直接请求 8081 会撞上跨域（CORS），所以把 /api 开头的请求转发给后端。
 * 这样页面里所有请求都写相对路径 /api/xxx，不用改代码就换后端地址。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址：默认 8081（IDEA 里启动的实例）；用 AI 验证实例时改 .env.local 里的 VITE_API_TARGET
  const target = env.VITE_API_TARGET || 'http://localhost:8081'

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target,
          changeOrigin: true
        }
      }
    },
    // 打包产物先放 frontend/dist，后面部署时再决定是拷进 jar 还是单独用 nginx 托管
    build: {
      outDir: 'dist',
      emptyOutDir: true
    }
  }
})
