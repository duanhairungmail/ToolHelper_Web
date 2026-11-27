import { request, RequestError } from '../utils/request.js'

export function createAesApi(transport = request) {
  let token, bootstrap
  async function session(signal) {
    if (!bootstrap) bootstrap = transport('/api/v1/session', { headers: { 'X-ToolHelper-Client': 'web' }, signal })
      .then(result => {
        if (!result.data?.token) throw new RequestError('本机会话无效', { code: 'INVALID_RESPONSE' })
        token = result.data.token
      }).finally(() => { bootstrap = undefined })
    await bootstrap
  }
  async function call(path, body, signal, retried = false) {
    if (!token) await session(signal)
    try {
      const result = await transport('/api/v1/aes' + path, {
        method: body === undefined ? 'GET' : 'POST', signal,
        headers: { 'X-ToolHelper-Session': token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      })
      return result.data
    } catch (failure) {
      if (failure.status === 401) {
        token = undefined
        // 仅无副作用的能力查询自动恢复会话，密码运算POST不重放。
        if (body === undefined && !retried) { await session(signal); return call(path, body, signal, true) }
      }
      throw failure
    }
  }
  return {
    capabilities: signal => call('/capabilities', undefined, signal),
    encrypt: (body, signal) => call('/encrypt', body, signal),
    decrypt: (body, signal) => call('/decrypt', body, signal),
    random: (body, signal) => call('/random', body, signal)
  }
}

export const aesApi = createAesApi()
