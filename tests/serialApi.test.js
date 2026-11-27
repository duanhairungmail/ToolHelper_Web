import test from 'node:test'
import assert from 'node:assert/strict'
import { createSerialApi } from '../src/api/serial.js'
import { RequestError } from '../src/utils/request.js'

test('串口API仅安全GET在服务重启后刷新会话重试', async () => {
  let tokenCalls = 0, portCalls = 0
  const api = createSerialApi(async (url) => {
    if (url === '/api/v1/session') return { data: { token: `token-${++tokenCalls}` } }
    if (url.endsWith('/ports') && ++portCalls === 1) throw new RequestError('session expired', { status: 401 })
    return { data: [{ name: 'COM3' }] }
  })
  assert.deepEqual(await api.ports(), [{ name: 'COM3' }])
  assert.equal(tokenCalls, 2)
  assert.equal(portCalls, 2)
})

test('串口write请求结果不确定时不自动重放', async () => {
  let writes = 0
  const api = createSerialApi(async (url, options = {}) => {
    if (url === '/api/v1/session') return { data: { token: 'token' } }
    if (options.method === 'POST') { writes++; throw new RequestError('outcome unknown', { code: 'REQUEST_OUTCOME_UNKNOWN' }) }
    return { data: null }
  })
  await assert.rejects(api.write('id', { mode: 'HEX', data: '01' }), /outcome unknown/)
  assert.equal(writes, 1)
})
