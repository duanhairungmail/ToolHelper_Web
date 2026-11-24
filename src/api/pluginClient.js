import { request, RequestError } from '../utils/request.js'

const base = '/api/v1'

// token 只驻留内存；后端重启后只重试安全 GET，不自动重放启动、连接或安装。
export function createPluginApi(pluginId, transport = request) {
  let token
  let sessionPromise
  async function session(signal) {
    if (!sessionPromise) {
      sessionPromise = transport(`${base}/session`, {
        headers: { 'X-ToolHelper-Client': 'web' }, signal
      }).then((response) => {
        if (!response.data?.token) throw new RequestError('本机会话响应无效', { code: 'INVALID_RESPONSE' })
        token = response.data.token
        return token
      }).finally(() => { sessionPromise = undefined })
    }
    return sessionPromise
  }

  async function call(path, { body, signal, key } = {}, retried = false) {
    if (!token) await session(signal)
    const mutation = body !== undefined
    try {
      const response = await transport(`${base}${path}`, {
        method: mutation ? 'POST' : 'GET', signal,
        headers: {
          'X-ToolHelper-Session': token,
          ...(mutation ? { 'Content-Type': 'application/json', 'Idempotency-Key': key || crypto.randomUUID() } : {})
        },
        ...(mutation ? { body: JSON.stringify(body) } : {})
      })
      return response.data
    } catch (error) {
      if (error.status === 401) {
        token = undefined
        if (!mutation && !retried) {
          await session(signal)
          return call(path, { signal }, true)
        }
      }
      throw error
    }
  }

  return {
    status: (signal) => call(`/plugins/${pluginId}`, { signal }),
    checkUpdate: (signal) => call(`/plugins/${pluginId}/update-check`, { signal }),
    task: (id, signal) => call(`/tasks/${encodeURIComponent(id)}`, { signal }),
    mutate: (operation, body = {}, signal, key) => call(`/plugins/${pluginId}/${operation}`, { body, signal, key })
  }
}
