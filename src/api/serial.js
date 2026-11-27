import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/serial'
export function createSerialApi(transport = request) {
  let token, bootstrap
  async function getToken(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', {
      headers: { 'X-ToolHelper-Client': 'web' }, signal
    }).then(result => {
      if (!result.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
      token = result.data.token
    }).finally(() => { bootstrap = undefined })
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
    write: (id, body, signal) => call(`/session/${encodeURIComponent(id)}/writes`, 'POST', body, signal),
    startAuto: (id, body, signal) => call(`/session/${encodeURIComponent(id)}/auto-send`, 'POST', body, signal),
    stopAuto: (id, signal) => call(`/session/${encodeURIComponent(id)}/auto-send`, 'DELETE', undefined, signal),
    events: (id, cursor, signal) => call(`/session/${encodeURIComponent(id)}/events?after=${cursor}`, 'GET', undefined, signal)
  }
}
export const serialApi = createSerialApi()
