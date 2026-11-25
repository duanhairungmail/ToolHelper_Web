import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/api-validation'

export function createApiValidationApi(transport = request, binaryTransport = (...args) => fetch(...args)) {
  let token
  let bootstrap
  async function session(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' }, signal })
      .then(response => {
        if (!response.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
        token = response.data.token
      }).finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, method = 'GET', body, signal, retried = false) {
    if (!token) await session(signal)
    try {
      const response = await transport(base + path, {
        method, signal, headers: { 'X-ToolHelper-Session': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {})
      })
      return response.data
    } catch (error) {
      if (error.status === 401) {
        token = undefined
        if (method === 'GET' && !retried) { await session(signal); return call(path, method, body, signal, true) }
      }
      throw error
    }
  }
  async function download(connectionId, view, format, signal, retried = false) {
    if (!token) await session(signal)
    const query = new URLSearchParams({ connectionId, view, format })
    const controller = new AbortController()
    const abort = () => controller.abort()
    if (signal?.aborted) abort()
    signal?.addEventListener('abort', abort, { once: true })
    const timeout = setTimeout(abort, 40000)
    try {
      const response = await binaryTransport(`${base}/export?${query}`, { headers: { 'X-ToolHelper-Session': token }, signal: controller.signal })
      if (response.status === 401 && !retried) { token = undefined; await session(signal); return download(connectionId, view, format, signal, true) }
      if (!response.ok) {
        let payload
        try { payload = await response.json() } catch { payload = {} }
        throw new RequestError(payload.error?.message || '导出失败', { status: response.status, code: payload.error?.code || 'EXPORT_FAILED', requestId: payload.requestId })
      }
      return { blob: await response.blob(), filename: `ToolHelper-${view}.${format}` }
    } catch (error) {
      if (error instanceof RequestError) throw error
      throw new RequestError('导出未完成，请检查本机服务后重试', { code: 'EXPORT_UNAVAILABLE' })
    } finally {
      clearTimeout(timeout)
      signal?.removeEventListener('abort', abort)
    }
  }
  return {
    state: signal => call('/session', 'GET', undefined, signal),
    endpoints: signal => call('/endpoints', 'GET', undefined, signal),
    login: (body, signal) => call('/login', 'POST', body, signal),
    logout: (connectionId, signal) => call('/logout', 'POST', { connectionId }, signal),
    verify: (body, signal) => call('/verify', 'POST', body, signal),
    query: (connectionId, mqtt, signal) => call(mqtt ? '/mqtt-topics' : '/device-ids', 'POST', { connectionId }, signal),
    startAuto: (body, signal) => call('/auto-detection', 'POST', body, signal),
    stopAuto: (connectionId, signal) => call(`/auto-detection?${new URLSearchParams({ connectionId })}`, 'DELETE', undefined, signal),
    cancel: (id, connectionId, signal) => call(`/tasks/${encodeURIComponent(id)}?${new URLSearchParams({ connectionId })}`, 'DELETE', undefined, signal),
    preview: (cron, signal) => call(`/cron-preview?${new URLSearchParams({ cron })}`, 'GET', undefined, signal),
    download
  }
}
export const apiValidation = createApiValidationApi()
