import { request, RequestError } from '../utils/request.js'

export function createKylinOpsApi(transport = request, binaryTransport = (...args) => fetch(...args)) {
  let token, bootstrap
  async function session(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' }, signal })
      .then(response => {
        if (!response.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
        token = response.data.token
      }).finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, method = 'GET', body, signal, retry = false) {
    if (!token) await session(signal)
    try {
      const response = await transport('/api/v1/kylin' + path, { method, signal,
        headers: { 'X-ToolHelper-Session': token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}) })
      return response.data
    } catch (failure) {
      if (failure.status === 401) {
        token = undefined
        // 即便是只读扫描也不重放POST；执行请求结果不明时必须先查询任务状态。
        if (method === 'GET' && !retry) { await session(signal); return call(path, method, body, signal, true) }
      }
      throw failure
    }
  }
  const id = value => encodeURIComponent(value)
  async function binary(path, body, signal) {
    if (!token) await session(signal)
    const controller = new AbortController(), abort = () => controller.abort()
    if (signal?.aborted) abort()
    signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(abort, 40000)
    try {
    const response = await binaryTransport('/api/v1/kylin' + path, { method: body ? 'POST' : 'GET', signal: controller.signal,
      headers: { 'X-ToolHelper-Session': token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) })
    if (!response.ok) {
      if (response.status === 401) token = undefined
      let payload
      try { payload = await response.json() } catch { payload = {} }
      throw new RequestError(payload.error?.message || '读取文件失败', { status: response.status, code: payload.error?.code || 'DOWNLOAD_FAILED', requestId: payload.requestId })
    }
    return await response.blob()
    } catch (failure) {
      if (failure instanceof RequestError) throw failure
      throw new RequestError('文件读取未完成，请刷新状态后重试', { code: 'DOWNLOAD_FAILED' })
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
  }
  return {
    activationCurrent: signal => call('/activation-tasks/current', 'GET', undefined, signal),
    activate: (body, signal) => call('/activation-tasks', 'POST', body, signal),
    continueActivation: (taskId, body, signal) => call(`/activation-tasks/${id(taskId)}/continue`, 'POST', body, signal),
    cancelActivation: (taskId, signal) => call(`/activation-tasks/${id(taskId)}`, 'DELETE', undefined, signal),
    activationQr: (taskId, signal) => binary(`/activation-tasks/${id(taskId)}/qr`, undefined, signal),
    activationExport: (body, signal) => binary('/activation-export', body, signal),
    capabilities: signal => call('/capabilities', 'GET', undefined, signal),
    current: signal => call('/connections/current', 'GET', undefined, signal),
    probe: (body, signal) => call('/host-key-probes', 'POST', body, signal),
    trust: (body, signal) => call('/host-key-trust', 'POST', body, signal),
    connect: (body, signal) => call('/connections', 'POST', body, signal),
    disconnect: (connectionId, signal) => call(`/connections/${id(connectionId)}`, 'DELETE', undefined, signal),
    backups: (connectionId, signal) => call(`/connections/${id(connectionId)}/backups`, 'GET', undefined, signal),
    scan: (body, signal) => call('/scans', 'POST', body, signal),
    plan: (body, signal) => call('/plans', 'POST', body, signal),
    execute: (body, signal) => call('/executions', 'POST', body, signal),
    active: signal => call('/tasks/active', 'GET', undefined, signal),
    task: (taskId, signal) => call(`/tasks/${id(taskId)}`, 'GET', undefined, signal),
    cancel: (taskId, signal) => call(`/tasks/${id(taskId)}`, 'DELETE', undefined, signal)
  }
}
export const kylinOpsApi = createKylinOpsApi()
