import { computed, onMounted, onUnmounted, ref } from 'vue'
import { pingApi } from '../api/ping.js'

const activeStatuses = new Set(['QUEUED', 'RUNNING', 'CANCELLING'])
const rememberedTaskKey = 'toolhelper.ping.taskId'
export const pingStatusLabels = { QUEUED: '等待执行', RUNNING: '检测中', CANCELLING: '停止中', SUCCEEDED: '完成', PARTIAL: '部分完成', CANCELLED: '已停止', FAILED: '任务失败' }
export const pingResultLabels = { RESPONSIVE: '有响应', PARTIAL_LOSS: '部分丢包', NO_REPLY: '无响应', ERROR: '探测错误', CANCELLED: '已停止' }
export function createPingDraft() { return { text: ref(''), fileBase64: ref('') } }
const sharedDraft = createPingDraft()

function rememberedTaskId() {
  try { return globalThis.sessionStorage?.getItem(rememberedTaskKey) || null }
  catch { return null }
}
function rememberTask(id) {
  try {
    if (id) globalThis.sessionStorage?.setItem(rememberedTaskKey, id)
    else globalThis.sessionStorage?.removeItem(rememberedTaskKey)
  } catch { /* 存储不可用时仍可正常使用当前页面任务。 */ }
}

export function usePing(api = pingApi, draft = sharedDraft) {
  const text = draft.text, fileBase64 = draft.fileBase64
  const capabilities = ref(null), parsed = ref(null), task = ref(null), pending = ref(false), available = ref(false)
  const error = ref(null), notice = ref(''), concurrency = ref(50), count = ref(4), timeoutMs = ref(1000)
  const sortBy = ref('input'), format = ref('xlsx')
  const running = computed(() => activeStatuses.has(task.value?.status))
  const rows = computed(() => {
    const source = [...(task.value?.results || [])]
    if (sortBy.value === 'ip') return source.sort((a, b) => ipNumber(a.ip) - ipNumber(b.ip))
    if (sortBy.value === 'status') return source.sort((a, b) => String(a.status).localeCompare(String(b.status)) || ipNumber(a.ip) - ipNumber(b.ip))
    if (sortBy.value === 'rtt') return source.sort((a, b) => (a.averageRttMs ?? Infinity) - (b.averageRttMs ?? Infinity))
    return source
  })
  const busy = computed(() => pending.value || running.value)
  let disposed = false, refreshLock = false, timer, revision = 0, initialized = false, restoreId = rememberedTaskId()
  const controller = new AbortController(), signal = controller.signal
  async function refresh() {
    if (disposed || refreshLock) return
    refreshLock = true
    const version = revision, previousTaskId = task.value?.taskId
    try {
      const [caps, active] = await Promise.all([api.capabilities(signal), api.active(signal)])
      if (disposed || version !== revision) return
      capabilities.value = caps
      if (!initialized) {
        concurrency.value = caps.limits?.defaultConcurrency || Math.min(50, caps.limits?.maxConcurrency || 200)
        initialized = true
      }
      available.value = true
      if (active) {
        task.value = active
        restoreId = active.taskId
        rememberTask(active.taskId)
      } else {
        const taskId = previousTaskId && activeStatuses.has(task.value?.status) ? previousTaskId : restoreId
        if (taskId) {
          // 活动槽释放或页面重新进入后，按短期保存的 ID 接回任务最终结果。
          try {
            task.value = await api.task(taskId, signal)
            restoreId = taskId
            rememberTask(taskId)
          } catch (failure) {
            if (failure.code !== 'PING_TASK_NOT_FOUND') throw failure
            if (restoreId === taskId) { restoreId = null; rememberTask(null) }
            task.value = null
          }
        }
      }
      error.value = null
    } catch (failure) {
      if (!disposed) { error.value = failure; available.value = false }
    } finally {
      refreshLock = false
      clearTimeout(timer)
      if (!disposed) timer = setTimeout(refresh, running.value ? 700 : 3000)
    }
  }
  async function perform(action) {
    if (disposed || pending.value) return
    revision++
    pending.value = true
    error.value = null
    notice.value = ''
    try { return await action() }
    catch (failure) { if (!disposed) error.value = failure; return undefined }
    finally { pending.value = false; if (!disposed) await refresh() }
  }
  async function parseTargets() {
    if (busy.value || !available.value) return
    const body = fileBase64.value ? { fileBase64: fileBase64.value } : { text: text.value }
    await perform(async () => {
      const result = await api.parse(body, signal)
      if (!disposed) { parsed.value = result; notice.value = '已解析并去重 ' + result.total + ' 个目标；尚未发送 Ping' }
    })
  }
  function clearParsed() { parsed.value = null; fileBase64.value = '' }
  async function start() {
    if (busy.value || !available.value) return
    if (!parsed.value?.targets?.length) { error.value = { code: 'NO_PARSED_TARGETS', message: '请先解析目标，再开始 Ping' }; return }
    const body = { requestKey: crypto.randomUUID(), targets: [...parsed.value.targets], concurrency: concurrency.value, count: count.value, timeoutMs: timeoutMs.value }
    await perform(async () => {
      const created = await api.submit(body, signal)
      if (!disposed) { task.value = created; restoreId = created.taskId; rememberTask(created.taskId) }
    })
  }
  async function stop() {
    if (!running.value || pending.value) return
    const id = task.value.taskId
    await perform(async () => { task.value = await api.cancel(id, signal) })
  }
  function clearResults() {
    if (busy.value) { error.value = { code: 'PING_TASK_BUSY', message: '请先停止任务并等待本机进程清理完成，再清空结果' }; return }
    task.value = null
    restoreId = null
    rememberTask(null)
    notice.value = '已清空当前页面结果；不影响本机任务记录'
  }
  async function download() {
    if (busy.value || !task.value?.results?.length) return
    await perform(async () => {
      const result = await api.download({ taskId: task.value.taskId, format: format.value }, signal)
      if (disposed) return
      const url = URL.createObjectURL(result.blob), anchor = document.createElement('a')
      anchor.href = url
      anchor.download = result.filename
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      notice.value = '导出文件已生成'
    })
  }
  async function selectFile(file) {
    fileBase64.value = ''
    parsed.value = null
    error.value = null
    if (!file) return
    const max = capabilities.value?.limits?.maxFileBytes || 2097152
    if (file.size > max) { error.value = { code: 'PING_FILE_TOO_LARGE', message: 'TXT 文件不能超过 ' + max + ' 字节' }; return }
    try {
      const bytes = new Uint8Array(await file.arrayBuffer())
      let binary = ''
      for (let start = 0; start < bytes.length; start += 0x8000) binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000))
      fileBase64.value = btoa(binary)
      text.value = ''
      notice.value = '已载入 ' + file.name + '，点击解析目标进行校验'
    } catch { error.value = { code: 'PING_FILE_READ_FAILED', message: 'TXT 文件读取失败' } }
  }
  function ipNumber(value) { return String(value || '').split('.').reduce((result, part) => result * 256 + Number(part), 0) }
  onMounted(refresh)
  onUnmounted(() => { disposed = true; clearTimeout(timer); controller.abort() })
  return { text, fileBase64, capabilities, parsed, task, rows, pending, available, running, busy, error, notice,
    concurrency, count, timeoutMs, sortBy, format, parseTargets, clearParsed, start, stop, clearResults, download, selectFile, refresh }
}
