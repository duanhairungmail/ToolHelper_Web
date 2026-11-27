import test from 'node:test'
import assert from 'node:assert/strict'
import { createPingApi } from '../src/api/ping.js'

test('群 Ping 解析与任务提交通过本机会话，非幂等 POST 和停止 DELETE 不自动重放', async () => {
  const calls = []
  const transport = async (url, options = {}) => {
    calls.push({ url, ...options })
    if (url === '/api/v1/session') return { data: { token: 'local-token' } }
    const failure = new Error('connection lost')
    failure.code = 'REQUEST_OUTCOME_UNKNOWN'
    throw failure
  }
  const api = createPingApi(transport)
  await assert.rejects(api.parse({ text: '127.0.0.1' }), /connection lost/)
  assert.equal(calls.filter(call => call.url.endsWith('/targets/parse')).length, 1)
  assert.equal(calls.at(-1).headers['X-ToolHelper-Session'], 'local-token')
  await assert.rejects(api.submit({ requestKey: 'request-1' }), /connection lost/)
  assert.equal(calls.filter(call => call.url.endsWith('/tasks')).length, 1)
  await assert.rejects(api.cancel('task-1'), /connection lost/)
  assert.equal(calls.filter(call => call.url.endsWith('/tasks/task-1')).length, 1)
})

test('会话过期后只重试安全 GET 一次，不重试任务写入', async () => {
  let sessions = 0, activeCalls = 0, submits = 0
  const transport = async (url, options = {}) => {
    if (url === '/api/v1/session') return { data: { token: 'token-' + ++sessions } }
    if (url.endsWith('/tasks/active') && ++activeCalls === 1) { const error = new Error('expired'); error.status = 401; throw error }
    if (url.endsWith('/tasks') && options.method === 'POST') { submits++; const error = new Error('expired'); error.status = 401; throw error }
    return { data: { taskId: null } }
  }
  const api = createPingApi(transport)
  await api.active()
  assert.equal(sessions, 2)
  await assert.rejects(api.submit({ requestKey: 'request-1' }), /expired/)
  assert.equal(submits, 1)
})

test('二进制导出返回文件且网络中断按结果不明处理', async () => {
  const before = globalThis.fetch
  try {
    const api = createPingApi(async url => url === '/api/v1/session' ? { data: { token: 'local-token' } } : { data: {} },
      async () => ({ ok: true, blob: async () => new Blob(['csv']) }))
    const result = await api.download({ taskId: 'task-1', format: 'csv' })
    assert.equal(result.filename, 'ToolHelper-ping.csv')
    assert.equal(await result.blob.text(), 'csv')
    const failed = createPingApi(async () => ({ data: { token: 'local-token' } }), async () => { throw new Error('offline') })
    await assert.rejects(failed.download({ taskId: 'task-1', format: 'csv' }), error => error.code === 'REQUEST_OUTCOME_UNKNOWN')
  } finally { globalThis.fetch = before }
})
