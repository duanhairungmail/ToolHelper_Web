import { request, RequestError } from '../utils/request.js'

export function createMqttModbusApi(transport = request) {
  let token, bootstrap
  async function getToken() {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' } })
      .then(result => {
        if (!result.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
        token = result.data.token
      }).finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, method = 'GET', body, retried = false) {
    if (!token) await getToken()
    try {
      const result = await transport('/api/v1/mqtt-modbus' + path, {
        method, headers: { 'X-ToolHelper-Session': token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      })
      return result.data
    } catch (error) {
      if (error.status === 401) {
        token = undefined
        if (method === 'GET' && !retried) { await getToken(); return call(path, method, body, true) }
      }
      throw error
    }
  }
  const session = id => `/sessions/${encodeURIComponent(id)}`
  return {
    capabilities: () => call('/capabilities'), list: () => call('/sessions'),
    open: body => call('/sessions', 'POST', body),
    close: id => call(session(id), 'DELETE'),
    profile: (id, body) => call(session(id) + '/profile', 'POST', body),
    heartbeat: id => call(session(id) + '/heartbeat', 'POST', {}),
    events: (id, after) => call(session(id) + `/events?after=${after}`)
  }
}
export const mqttModbusApi = createMqttModbusApi()
