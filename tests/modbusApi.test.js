import test from 'node:test'
import assert from 'node:assert/strict'
import { createModbusApi } from '../src/api/modbus.js'

test('Modbus API sends guarded requests and does not replay operation writes', async () => {
  const calls = []
  const transport = async (url, options = {}) => {
    calls.push({ url, ...options })
    if (url === '/api/v1/session') return { data: { token: 'local-token' } }
    if (url.endsWith('/ports')) return { data: { ports: [], occupiedBy: '' } }
    if (options.method === 'POST' && url.endsWith('/operations')) {
      const error = new Error('connection lost'); error.code = 'REQUEST_OUTCOME_UNKNOWN'; throw error
    }
    return { data: { ok: true } }
  }
  const api = createModbusApi(transport)
  await api.ports()
  assert.equal(calls.at(-1).headers['X-ToolHelper-Session'], 'local-token')
  await assert.rejects(api.operate('session-1', { type: 'RESET', address: 2, confirmed: true }), /connection lost/)
  assert.equal(calls.filter(call => call.url.endsWith('/operations')).length, 1)
  assert.equal(calls.at(-1).body, JSON.stringify({ type: 'RESET', address: 2, confirmed: true }))
})

test('Modbus串口列表解析后端包装结构，刷新可发现新插入的USB串口', async () => {
  let available = []
  const api = createModbusApi(async url => {
    if (url === '/api/v1/session') return { data: { token: 'local-token' } }
    assert.equal(url, '/api/v1/modbus/ports')
    return { data: { ports: available, occupiedBy: '' } }
  })
  assert.deepEqual(await api.ports(), [])
  available = [{ name: 'COM7', description: 'USB Serial Port' }]
  assert.deepEqual(await api.ports(), available)
  available = []
  assert.deepEqual(await api.ports(), [])
})

test('Modbus串口列表格式错误明确报错，不伪装成未发现串口', async () => {
  const api = createModbusApi(async url => url === '/api/v1/session'
    ? { data: { token: 'local-token' } }
    : { data: { occupiedBy: '' } })
  await assert.rejects(api.ports(), { code: 'INVALID_RESPONSE', message: '串口列表响应格式无效' })
})

test('Modbus API refreshes the token and retries only GET requests once', async () => {
  let bootstraps = 0, states = 0
  const transport = async (url, options = {}) => {
    if (url === '/api/v1/session') return { data: { token: `token-${++bootstraps}` } }
    if (url.endsWith('/state') && ++states === 1) { const error = new Error('expired'); error.status = 401; throw error }
    return { data: { state: 'open' } }
  }
  const api = createModbusApi(transport)
  assert.deepEqual(await api.state('session-1'), { state: 'open' })
  assert.equal(bootstraps, 2); assert.equal(states, 2)
})
