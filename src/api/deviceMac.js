import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/device-mac'

// 仅安全 GET 在本机会话更新后重试，保存、查询和导出都不自动重放。
export function createDeviceMacApi(transport = request, binaryTransport = (...args) => fetch(...args)) {
  let token, bootstrap
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
      const response = await transport(base + path, { method, signal,
        headers: { 'X-ToolHelper-Session': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}) })
      return response.data
    } catch (error) {
      if (error.status === 401) {
        token = undefined
        if (method === 'GET' && !retried) { await session(signal); return call(path, method, body, signal, true) }
      }
      throw error
    }
  }
  async function download(body, signal) {
    if (!token) await session(signal)
    const controller = new AbortController()
    const abort = () => controller.abort()
    if (signal?.aborted) abort()
    signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(abort, 40000)
    try {
      const response = await binaryTransport(base + '/export', { method: 'POST', headers: { 'X-ToolHelper-Session': token, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: controller.signal })
      if (!response.ok) {
        if (response.status === 401) token = undefined
        let payload
        try { payload = await response.json() } catch { payload = {} }
        throw new RequestError(payload.error?.message || '导出失败', { status: response.status, code: payload.error?.code || 'EXPORT_FAILED', requestId: payload.requestId })
      }
      return { blob: await response.blob(), filename: `ToolHelper-device-mac.${body.format}` }
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
  }
  return {
    capabilities: signal => call('/capabilities', 'GET', undefined, signal),
    ledger: signal => call('/ledger', 'GET', undefined, signal),
    save: (body, signal) => call('/ledger/save', 'POST', body, signal),
    submit: (body, signal) => call('/queries', 'POST', body, signal),
    active: signal => call('/queries/active', 'GET', undefined, signal),
    task: (id, signal) => call(`/queries/${encodeURIComponent(id)}`, 'GET', undefined, signal),
    cancel: (id, signal) => call(`/queries/${encodeURIComponent(id)}`, 'DELETE', undefined, signal),
    download
  }
}
export const deviceMacApi = createDeviceMacApi()
