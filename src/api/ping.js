import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/ping'
export function createPingApi(transport = request, binaryTransport = (...args) => fetch(...args)) {
  let token, bootstrap
  async function session(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' }, signal })
      .then(result => {
        if (!result.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
        token = result.data.token
      }).finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, method = 'GET', body, signal, retried = false) {
    if (!token) await session(signal)
    try {
      const result = await transport(base + path, { method, signal,
        headers: { 'X-ToolHelper-Session': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}) })
      return result.data
    } catch (failure) {
      if (failure.status === 401) {
        token = undefined
        if (method === 'GET' && !retried) { await session(signal); return call(path, method, body, signal, true) }
      }
      throw failure
    }
  }
  async function download(body, signal) {
    if (!token) await session(signal)
    const controller = new AbortController(), abort = () => controller.abort()
    if (signal?.aborted) abort()
    signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(abort, 40000)
    try {
      const response = await binaryTransport(base + '/export', { method: 'POST', signal: controller.signal,
        headers: { 'X-ToolHelper-Session': token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      if (!response.ok) {
        if (response.status === 401) token = undefined
        let payload
        try { payload = await response.json() } catch { payload = {} }
        throw new RequestError(payload.error?.message || '导出失败', { status: response.status, code: payload.error?.code || 'EXPORT_FAILED', requestId: payload.requestId })
      }
      return { blob: await response.blob(), filename: 'ToolHelper-ping.' + body.format }
    } catch (failure) {
      if (failure instanceof RequestError) throw failure
      throw new RequestError('导出请求结果无法确认，请勿连续重试', { code: 'REQUEST_OUTCOME_UNKNOWN' })
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
  }
  const path = id => '/tasks/' + encodeURIComponent(id)
  return {
    capabilities: signal => call('/capabilities', 'GET', undefined, signal),
    parse: (body, signal) => call('/targets/parse', 'POST', body, signal),
    active: signal => call('/tasks/active', 'GET', undefined, signal),
    task: (id, signal) => call(path(id), 'GET', undefined, signal),
    submit: (body, signal) => call('/tasks', 'POST', body, signal),
    cancel: (id, signal) => call(path(id), 'DELETE', undefined, signal),
    download
  }
}
export const pingApi = createPingApi()
