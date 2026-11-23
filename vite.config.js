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

function createLocalApiProxy() {
  return {
    target: 'http://127.0.0.1:18081', changeOrigin: true,
    configure(proxy) {
      // Vite 默认返回空的纯文本 500；保留 API 的 JSON 错误格式，避免掩盖连接失败。
      proxy.on('error', (_error, req, res) => {
        if (!('writeHead' in res) || res.headersSent || res.writableEnded || res.destroyed) return
        const mutation = req.method === 'POST'
        res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
        res.end(JSON.stringify({ error: {
          code: mutation ? 'REQUEST_OUTCOME_UNKNOWN' : 'SERVICE_UNAVAILABLE',
          message: mutation ? '请求结果无法确认，请刷新插件状态核对后再操作，勿连续重试'
            : '本机 Java 服务不可用，请检查后端是否已成功启动并监听 18081 端口',
          retryable: !mutation
        } }))
      })
    }
  }
}

export default defineConfig({
  plugins: [vue(), { name: 'local-api-boundary', configureServer: protectLocalApi, configurePreviewServer: protectLocalApi }],
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    watch: { ignored: ['**/backend-java/**', '**/.runtime/**', '**/.m2-cache/**'] },
    proxy: { '/api': createLocalApiProxy() }
  },
  preview: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: { '/api': createLocalApiProxy() }
  },
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  }
})
