import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { once } from 'node:events'
import { createServer } from 'vite'
import config from '../vite.config.js'

async function startProxy(t, target, options = config.server) {
  const server = await createServer({
    ...config, configFile: false, logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
    server: {
      ...options, port: 0, strictPort: false, hmr: false, watch: null,
      proxy: { '/api': { ...options.proxy['/api'], target } }
    }
  })
  t.after(() => server.close())
  await server.listen()
  return `http://127.0.0.1:${server.httpServer.address().port}`
}

async function readResponse(base, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.request(`${base}/api/v1/session`, { method, agent: false }, (response) => {
      let body = ''
      response.setEncoding('utf8')
      response.on('data', (chunk) => { body += chunk })
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: JSON.parse(body) }))
    })
    req.on('error', reject)
    req.end()
  })
}

test('后端未监听时，开发和预览代理返回 JSON 服务错误', async (t) => {
  // 先由系统分配空闲端口再关闭，模拟 Java 尚未启动；不访问用户的 18081 服务。
  const unavailable = http.createServer()
  unavailable.listen(0, '127.0.0.1')
  await once(unavailable, 'listening')
  const target = `http://127.0.0.1:${unavailable.address().port}`
  await new Promise((resolve) => unavailable.close(resolve))
  for (const options of [config.server, config.preview]) {
    const base = await startProxy(t, target, options)
    const response = await readResponse(base)
    assert.equal(response.status, 503)
    assert.match(response.headers['content-type'], /application\/json/)
    assert.equal(response.body.error.code, 'SERVICE_UNAVAILABLE')
    assert.equal(response.body.error.retryable, true)
  }
})

test('正常业务 JSON 原样转发，POST 断线保留结果不确定且不重发', async (t) => {
  let mutations = 0
  const upstream = http.createServer((req, res) => {
    if (req.method === 'POST') {
      mutations++
      req.socket.destroy()
      return
    }
    res.writeHead(409, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ requestId: 'proxy-test', error: { code: 'PLUGIN_IN_USE' } }))
  })
  upstream.listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  t.after(() => new Promise((resolve) => upstream.close(resolve)))
  const base = await startProxy(t, `http://127.0.0.1:${upstream.address().port}`)
  const business = await readResponse(base)
  assert.equal(business.status, 409)
  assert.equal(business.body.requestId, 'proxy-test')
  assert.equal(business.body.error.code, 'PLUGIN_IN_USE')
  const failed = await readResponse(base, 'POST')
  assert.equal(failed.status, 503)
  assert.equal(failed.body.error.code, 'REQUEST_OUTCOME_UNKNOWN')
  assert.equal(failed.body.error.retryable, false)
  assert.equal(mutations, 1)
})
