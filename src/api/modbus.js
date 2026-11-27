import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/modbus'

export function createModbusApi(transport = request) {
  let token, bootstrap
  async function getToken(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' }, signal })
      .then(result => { if (!result.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' }); token = result.data.token })
      .finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, method = 'GET', body, signal, retried = false) {
    if (!token) await getToken(signal)
    try {
      const result = await transport(base + path, {
        method, signal,
        headers: { 'X-ToolHelper-Session': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {})
      })
      return result.data
    } catch (error) {
      if (error.status === 401) {
        token = undefined
        if (method === 'GET' && !retried) { await getToken(signal); return call(path, method, body, signal, true) }
      }
      throw error
    }
  }
  return {
    capabilities: signal => call('/capabilities', 'GET', undefined, signal),
    ports: signal => call('/ports', 'GET', undefined, signal),
    session: signal => call('/session', 'GET', undefined, signal),
    open: (body, signal) => call('/session', 'POST', body, signal),
    close: (id, signal) => call(`/session/${encodeURIComponent(id)}`, 'DELETE', undefined, signal),
    state: (id, signal) => call(`/session/${encodeURIComponent(id)}/state`, 'GET', undefined, signal),
    operate: (id, body, signal) => call(`/session/${encodeURIComponent(id)}/operations`, 'POST', body, signal),
    cancel: (id, operationId, signal) => call(`/session/${encodeURIComponent(id)}/operations/${encodeURIComponent(operationId)}`, 'DELETE', undefined, signal),
    startPolling: (id, signal) => call(`/session/${encodeURIComponent(id)}/polling`, 'POST', {}, signal),
    stopPolling: (id, signal) => call(`/session/${encodeURIComponent(id)}/polling`, 'DELETE', undefined, signal),
    heartbeat: (id, signal) => call(`/session/${encodeURIComponent(id)}/heartbeat`, 'POST', {}, signal),
    events: (id, cursor, signal) => call(`/session/${encodeURIComponent(id)}/events?after=${cursor}`, 'GET', undefined, signal)
  }
}

export const modbusApi = createModbusApi()
