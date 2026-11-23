export class RequestError extends Error {
  constructor(message, { status = 0, code = 'SERVICE_UNAVAILABLE', requestId, retryable = false } = {}) {
    super(message)
    this.name = 'RequestError'
    Object.assign(this, { status, code, requestId, retryable })
  }
}

export async function request(url, options = {}) {
  const { timeout = 40000, ...fetchOptions } = options
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, timeout)
  try {
    const response = await fetch(url, { ...fetchOptions, signal: controller.signal })
    let payload
    try { payload = await response.json() } catch {
      const mutation = fetchOptions.method === 'POST'
      throw new RequestError(mutation ? '请求结果无法确认，请刷新插件状态核对后再操作，勿连续重试'
        : '本机服务未返回有效 JSON，请检查 Java 服务和开发代理', {
        status: response.status, code: mutation ? 'REQUEST_OUTCOME_UNKNOWN' : 'INVALID_RESPONSE', requestId: response.headers.get('X-Request-Id')
      })
    }
    if (!response.ok) {
      throw new RequestError(payload.error?.message || `请求失败：${response.status}`, {
        status: response.status, code: payload.error?.code || 'HTTP_ERROR',
        requestId: payload.requestId || response.headers.get('X-Request-Id'), retryable: payload.error?.retryable
      })
    }
    return payload
  } catch (error) {
    if (error instanceof RequestError) throw error
    throw new RequestError(fetchOptions.method === 'POST'
      ? '请求结果无法确认，请刷新插件状态核对后再操作，勿连续重试'
      : '本机服务不可用，请检查 Java 服务是否已启动', {
      code: fetchOptions.method === 'POST' ? 'REQUEST_OUTCOME_UNKNOWN' : 'SERVICE_UNAVAILABLE'
    })
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', abort)
  }
}
