import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { apiValidation } from '../api/apiValidation.js'

export function useApiValidation({ append = () => {}, clear = () => {} } = {}, api = apiValidation) {
  const snapshot = ref(null), endpoints = ref([]), error = ref(null), pending = ref(false), available = ref(false)
  const tab = ref('results'), dialog = ref(''), selectedIds = ref([]), cron = ref('0 */5 * * * ?'), nextRun = ref('')
  const form = reactive({ host: '', port: 8088, scheme: 'https', username: '', password: '', credentialMode: 'AES_CBC', loginFormat: 'LEGACY_QUERY', allowInsecureHttp: false })
  const connected = computed(() => Boolean(snapshot.value?.connected))
  const running = computed(() => Boolean(snapshot.value?.busy || snapshot.value?.auto?.running))
  const busy = computed(() => pending.value || running.value)
  const canRun = computed(() => available.value && connected.value && !busy.value)
  let disposed = false, timer, sequence = 0, observed = '', observedConnection
  const controller = new AbortController()
  const signal = controller.signal
  function schedule() { clearTimeout(timer); if (!disposed) timer = setTimeout(refresh, running.value ? 1000 : 4000) }
  function apply(data) {
    snapshot.value = data; available.value = true
    if (data.connectionId !== observedConnection) {
      observedConnection = data.connectionId; observed = ''
      if (data.target) {
        const origin = new URL(data.target)
        form.host = origin.hostname.replace(/^\[|\]$/g, ''); form.port = Number(origin.port || (origin.protocol === 'https:' ? 443 : 80)); form.scheme = origin.protocol.slice(0, -1)
      }
    }
    const job = data.currentTask
    const marker = job ? `${job.taskId}:${job.status}` : ''
    if (marker && marker !== observed && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status)) {
      append(`${job.operation} · ${job.status} · ${job.message || '已完成'}${job.errorCode ? ' · ' + job.errorCode : ''}`)
    }
    observed = marker
  }
  async function refresh() {
    const current = ++sequence
    try {
      const data = await api.state(signal)
      // 服务稍后启动时重载目录，避免首次挂载失败后始终无法选择接口。
      if (!endpoints.value.length) {
        const catalog = await api.endpoints(signal)
        if (!disposed && current === sequence) endpoints.value = catalog
      }
      if (!disposed && current === sequence) { apply(data); if (!pending.value) error.value = null }
    } catch (failure) { if (!disposed && current === sequence) { available.value = false; error.value = failure } }
    finally { schedule() }
  }
  async function perform(label, action) {
    if (pending.value || disposed) return
    pending.value = true; error.value = null
    try {
      await action()
      if (!disposed) append(label)
    } catch (failure) { if (!disposed) { error.value = failure; append(`${label}失败 · ${failure.code || 'REQUEST_FAILED'} · ${failure.message}`) } }
    finally {
      pending.value = false
      if (!disposed) {
        const previous = error.value
        await refresh()
        if (previous) error.value = previous
      }
    }
  }
  async function login() {
    if (pending.value || running.value) return
    const body = { ...form }
    form.password = ''
    try { await perform('目标登录', () => api.login(body, signal)) }
    finally { delete body.password }
  }
  const logout = () => perform('退出目标登录', () => api.logout(snapshot.value.connectionId, signal))
  function openSelection(mode) {
    if (!canRun.value) return
    selectedIds.value = endpoints.value.filter(item => !item.sideEffect).map(item => item.id)
    dialog.value = mode; nextRun.value = ''
  }
  async function submitSelection() {
    if (!canRun.value || !selectedIds.value.length) return
    const automatic = dialog.value === 'auto'
    const sideEffects = endpoints.value.some(item => item.sideEffect && selectedIds.value.includes(item.id))
    if (sideEffects && (automatic || !window.confirm('所选操作包含设备复位或参数写入，可能影响真实设备。确认目标与维护窗口后，仍要执行吗？'))) return
    const body = { connectionId: snapshot.value.connectionId, endpointIds: [...selectedIds.value] }
    dialog.value = ''; tab.value = 'results'
    await perform(automatic ? '启动自动检测' : '提交验证任务', () => automatic
      ? api.startAuto({ ...body, cron: cron.value }, signal)
      : api.verify({ ...body, confirmedSideEffects: sideEffects }, signal))
  }
  const query = mqtt => {
    if (!canRun.value) return
    tab.value = mqtt ? 'topics' : 'devices'
    return perform(mqtt ? '获取MQTT主题' : '获取设备ID', () => api.query(snapshot.value.connectionId, mqtt, signal))
  }
  const stop = () => perform('停止自动检测', () => api.stopAuto(snapshot.value.connectionId, signal))
  const cancel = () => perform('取消当前任务', () => api.cancel(snapshot.value.currentTask.taskId, snapshot.value.connectionId, signal))
  const preview = () => perform('校验Cron', async () => { const result = await api.preview(cron.value, signal); nextRun.value = `${result.nextRun}（${result.zone}）` })
  function clearLogs() { clear() } // 用户确认：只清日志区，不清表格、不取消任务。
  async function download(format) {
    if (!snapshot.value?.connectionId || busy.value) return
    await perform('导出当前表格', async () => {
      const result = await api.download(snapshot.value.connectionId, tab.value, format, signal)
      if (disposed) return
      const url = URL.createObjectURL(result.blob), anchor = document.createElement('a')
      anchor.href = url; anchor.download = result.filename; document.body.appendChild(anchor); anchor.click(); anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    })
  }
  onMounted(refresh)
  onUnmounted(() => { disposed = true; sequence++; clearTimeout(timer); controller.abort(); form.password = '' })
  return { snapshot, endpoints, error, pending, available, tab, dialog, selectedIds, cron, nextRun, form, connected, running, busy, canRun,
    refresh, login, logout, openSelection, submitSelection, query, stop, cancel, preview, clearLogs, download }
}
