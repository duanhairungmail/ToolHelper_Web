import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiValidationApi } from '../src/api/apiValidation.js'
import { request } from '../src/utils/request.js'

test('验证接口仅GET刷新本机会话后重试，登录和停止请求不重放', async () => {
  const calls = [], counts = new Map()
  const api = createApiValidationApi(async (url, options) => {
    calls.push({ url, options })
    if (url === '/api/v1/session') return { data: { token: 'local-session' } }
    const count = (counts.get(url) || 0) + 1; counts.set(url, count)
    if (count === 1) throw { status: 401, code: 'SESSION_REQUIRED' }
    return { data: { connected: false } }
  })
  assert.deepEqual(await api.state(), { connected: false })
  await assert.rejects(api.login({ password: 'fixture-password' }), { code: 'SESSION_REQUIRED' })
  await assert.rejects(api.stopAuto('connection'), { code: 'SESSION_REQUIRED' })
  assert.equal(counts.get('/api/v1/api-validation/session'), 2)
  assert.equal(counts.get('/api/v1/api-validation/login'), 1)
  assert.equal(counts.get('/api/v1/api-validation/auto-detection?connectionId=connection'), 1)
  assert.equal(calls.find(call => call.url.endsWith('/login')).options.body, '{"password":"fixture-password"}')
  assert.equal(calls.some(call => call.url.includes('fixture-password')), false)
})

test('二进制导出带本机会话且保持业务错误请求标识', async () => {
  let received
  const api = createApiValidationApi(async () => ({ data: { token: 'local' } }), async (url, options) => {
    received = { url, options }
    return new Response(JSON.stringify({ requestId: 'export-id', error: { code: 'NO_EXPORT_DATA', message: '无数据' } }), { status: 409 })
  })
  await assert.rejects(api.download('connection', 'topics', 'xlsx'), { code: 'NO_EXPORT_DATA', requestId: 'export-id' })
  assert.equal(received.options.headers['X-ToolHelper-Session'], 'local')
  assert.match(received.url, /view=topics&format=xlsx/)
})

test('DELETE断线按结果不确定处理且只发送一次', async () => {
  const before = globalThis.fetch
  let count = 0
  globalThis.fetch = async () => { count++; throw new TypeError('offline') }
  try {
    await assert.rejects(request('/api/v1/api-validation/auto-detection', { method: 'DELETE' }), { code: 'REQUEST_OUTCOME_UNKNOWN' })
    assert.equal(count, 1)
  } finally { globalThis.fetch = before }
})

test('导出成功返回Blob，离开页面中止导出等待并提供明确错误', async () => {
  const api = createApiValidationApi(async () => ({ data: { token: 'local' } }), async () => new Response('PK-fixture', { status: 200 }))
  const result = await api.download('c', 'devices', 'xlsx')
  assert.equal(result.filename, 'ToolHelper-devices.xlsx'); assert.equal(await result.blob.text(), 'PK-fixture')
  const controller = new AbortController()
  const interrupted = createApiValidationApi(async () => ({ data: { token: 'local' } }), async (_, { signal }) => {
    await new Promise((resolve, reject) => {
      if (signal.aborted) reject(Error('cancelled'))
      else signal.addEventListener('abort', () => reject(Error('cancelled')), { once: true })
    })
  })
  const pending = interrupted.download('c', 'topics', 'docx', controller.signal)
  controller.abort()
  await assert.rejects(pending, { code: 'EXPORT_UNAVAILABLE' })
})
