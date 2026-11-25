import { request, RequestError } from '../utils/request.js'

const base = '/api/v1/druid'
export function createDruidScanApi(transport = request, binaryTransport = (...args) => fetch(...args)) {
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
    } catch (failure) {
      if (failure.status === 401) {
        token = undefined
        // 检测/凭据提交结果不明时只核对状态，不自动重放。
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
      return { blob: await response.blob(), filename: `ToolHelper-druid.${body.format}` }
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
  }
  const path = id => `/scans/${encodeURIComponent(id)}`
  return {
    capabilities: signal => call('/capabilities', 'GET', undefined, signal),
    history: signal => call('/scans', 'GET', undefined, signal),
    active: signal => call('/scans/active', 'GET', undefined, signal),
    task: (id, signal) => call(path(id), 'GET', undefined, signal),
    results: (id, page, signal) => call(`${path(id)}/results?page=${page}&size=50`, 'GET', undefined, signal),
    detail: (id, targetId, signal) => call(`${path(id)}/results/${encodeURIComponent(targetId)}`, 'GET', undefined, signal),
    submit: (body, signal) => call('/scans', 'POST', body, signal),
    credentials: (body, signal) => call('/credential-checks', 'POST', body, signal),
    cancel: (id, signal) => call(path(id), 'DELETE', undefined, signal),
    clear: signal => call('/results', 'DELETE', undefined, signal), download
  }
}
export const druidScanApi = createDruidScanApi()
