import { request, RequestError } from '../utils/request.js'

export function createKylinOpsApi(transport = request) {
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
  return {
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
