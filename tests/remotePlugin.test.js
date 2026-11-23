import test from 'node:test'
import assert from 'node:assert/strict'
import { createRemotePluginApi } from '../src/api/remotePlugin.js'
import { RequestError, request } from '../src/utils/request.js'

test('并发 GET 共用内存 session，保留服务返回数据', async () => {
  let sessions = 0
  const api = createRemotePluginApi(async (url, options) => {
    if (url.endsWith('/session')) { sessions++; return { data: { token: 'session-token' } } }
    assert.equal(options.headers['X-ToolHelper-Session'], 'session-token')
    return { data: { state: 'NOT_INSTALLED' } }
  })
  const results = await Promise.all([api.status(), api.status()])
  assert.equal(sessions, 1)
  assert.equal(results[0].state, 'NOT_INSTALLED')
})

test('服务重启的 401 只允许安全 GET 重新获取 session 并重试一次', async () => {
  let sessions = 0
  let calls = 0
  const api = createRemotePluginApi(async (url) => {
    if (url.endsWith('/session')) return { data: { token: `token-${++sessions}` } }
    if (++calls === 1) throw new RequestError('expired', { status: 401 })
    return { data: { state: 'INSTALLED' } }
  })
  assert.equal((await api.status()).state, 'INSTALLED')
  assert.equal(sessions, 2)
  assert.equal(calls, 2)
})

test('启动/连接/安装 POST 不自动重试，参数通过 JSON 传递', async () => {
  for (const operation of ['launch', 'connect', 'install']) {
    let mutations = 0
    const body = { protocol: 'RDP', host: '2001:db8::1', port: 3399, username: 'DOMAIN\\user@name' }
    const api = createRemotePluginApi(async (url, options) => {
      if (url.endsWith('/session')) return { data: { token: 'token' } }
      mutations++
      assert.equal(options.method, 'POST')
      assert.deepEqual(JSON.parse(options.body), body)
      assert.equal(options.headers['Idempotency-Key'], 'explicit-idempotency-key')
      throw new RequestError('expired', { status: 401, code: 'SESSION_REQUIRED' })
    })
    await assert.rejects(api.mutate(operation, body, undefined, 'explicit-idempotency-key'), { code: 'SESSION_REQUIRED' })
    assert.equal(mutations, 1)
  }
})

test('HTTP 错误保留业务码和请求标识', async () => {
  const previous = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify({ requestId: 'request-1', error: {
    code: 'PLUGIN_IN_USE', message: '请先关闭 electerm', retryable: true
  } }), { status: 409 })
  try {
    await assert.rejects(request('/api'), { status: 409, code: 'PLUGIN_IN_USE', requestId: 'request-1', retryable: true })
  } finally { globalThis.fetch = previous }
})

test('网络故障时 POST 提示结果不确定且不自动重发', async () => {
  const previous = globalThis.fetch
  let calls = 0
  globalThis.fetch = async () => { calls++; throw new TypeError('network failed') }
  try {
    await assert.rejects(request('/api', { method: 'POST', body: '{}' }), { code: 'REQUEST_OUTCOME_UNKNOWN' })
    assert.equal(calls, 1)
    await assert.rejects(request('/api'), { code: 'SERVICE_UNAVAILABLE' })
    globalThis.fetch = async () => new Response('upstream unavailable', { status: 502 })
    await assert.rejects(request('/api', { method: 'POST', body: '{}' }), { code: 'REQUEST_OUTCOME_UNKNOWN' })
  } finally { globalThis.fetch = previous }
})
