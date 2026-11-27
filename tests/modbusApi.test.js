import test from 'node:test'
import assert from 'node:assert/strict'
import { createModbusApi } from '../src/api/modbus.js'

test('Modbus API sends guarded requests and does not replay operation writes', async () => {
  const calls = []
  const transport = async (url, options = {}) => {
    calls.push({ url, ...options })
    if (url === '/api/v1/session') return { data: { token: 'local-token' } }
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
