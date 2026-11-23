import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

function protectLocalApi(server) {
  server.middlewares.use((req, res, next) => {
    // 即便有人用 --host 覆盖监听配置，开发代理也不能向局域网暴露本机能力。
    if (req.url?.startsWith('/api/') && !['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress)) {
      res.writeHead(403, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { code: 'LOCAL_REQUEST_REQUIRED', message: '只允许本机 API 请求' } }))
      return
    }
    next()
  })
}

export default defineConfig({
  plugins: [vue(), { name: 'local-api-boundary', configureServer: protectLocalApi, configurePreviewServer: protectLocalApi }],
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    watch: { ignored: ['**/backend-java/**', '**/.runtime/**', '**/.m2-cache/**'] },
    proxy: { '/api': { target: 'http://127.0.0.1:18081', changeOrigin: true } }
  },
  preview: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:18081', changeOrigin: true } }
  },
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  }
})
