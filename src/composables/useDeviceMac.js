import { computed, onMounted, onUnmounted, ref } from 'vue'
import { deviceMacApi } from '../api/deviceMac.js'

export const macColumns = [
  ['index', '序号'], ['stationName', '站名'], ['ipAddress', 'IP地址'], ['deviceName', '设备名'],
  ['deviceModel', '设备型号'], ['serialNumber', '设备序列号'], ['macAddress', '设备MAC地址'], ['location', '安装位置'],
  ['status', '观察状态'], ['checkedAt', '查询时间']
]
export function createMacDraft() {
  return { rows: ref([]), revision: ref('initial'), baseline: ref('[]'), loaded: ref(false), task: ref(null), applied: new Set() }
}
// 草稿只在当前页面进程内存保留；切换工具后返回可继续查看，不写 localStorage。
const sharedDraft = createMacDraft()
const copy = value => JSON.parse(JSON.stringify(value))
const activeStatus = value => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(value)
export function useDeviceMac(api = deviceMacApi, draft = sharedDraft) {
  const { rows, revision, baseline, loaded, task, applied } = draft
  const capabilities = ref(null), error = ref(null), pending = ref(false), available = ref(false), notice = ref('')
  const selected = ref([]), exportOpen = ref(false), format = ref('xlsx'), exportScope = ref('all')
  const columns = ref(macColumns.slice(0, 8).map(item => item[0]))
  const dirty = computed(() => JSON.stringify(rows.value) !== baseline.value)
  const running = computed(() => activeStatus(task.value?.status))
  const busy = computed(() => pending.value || running.value)
  const canQuery = computed(() => available.value && loaded.value && capabilities.value?.supported && !busy.value)
  let timer, disposed = false, refreshing = false
  const controller = new AbortController(), signal = controller.signal
  function apply(job) {
    if (!job) { task.value = null; return }
    task.value = job
    for (const result of job.results || []) {
      const key = `${job.taskId}:${result.recordId}`
      if (applied.has(key)) continue
      const row = rows.value.find(item => item.recordId === result.recordId && item.ipAddress.trim() === result.ipAddress)
      if (row && result.observation) row.lastObservation = copy(result.observation)
      applied.add(key)
    }
    if (!activeStatus(job.status)) notice.value = `查询${job.status === 'CANCELLED' ? '已取消' : job.status === 'FAILED' ? '失败' : '结束'}：已处理 ${job.done}/${job.total} 行${job.message ? ' · ' + job.message : ''}`
  }
  function acceptLedger(ledger) {
    rows.value = copy(ledger.records); revision.value = ledger.revision
    baseline.value = JSON.stringify(rows.value); loaded.value = true
    applied.clear(); task.value = null
  }
  async function refresh() {
    if (disposed || refreshing) return
    refreshing = true
    try {
      const caps = await api.capabilities(signal)
      if (disposed) return
      capabilities.value = caps
      if (!loaded.value) {
        const ledger = await api.ledger(signal)
        if (disposed) return
        acceptLedger(ledger)
      }
      const active = await api.active(signal)
      let job = active
      if (!job && task.value?.taskId && activeStatus(task.value.status)) {
        try { job = await api.task(task.value.taskId, signal) }
        catch (failure) { if (failure.status === 404) { if (!disposed) { task.value = null; notice.value = '任务已失效或服务已重启；未自动重发查询' } } else throw failure }
      }
      if (!disposed && job) apply(job)
      if (!disposed) available.value = true
    } catch (failure) { if (!disposed) { available.value = false; error.value = failure } }
    finally {
      refreshing = false
      clearTimeout(timer)
      if (!disposed) timer = setTimeout(refresh, running.value ? 1000 : 5000)
    }
  }
  async function perform(action) {
    if (disposed || pending.value) return false
    pending.value = true; error.value = null; notice.value = ''
    try { await action(); return true }
    catch (failure) { if (!disposed) error.value = failure; return false }
    finally { pending.value = false; if (!disposed) await refresh() }
  }
  function addRow() {
    if (busy.value || !loaded.value || rows.value.length >= (capabilities.value?.maxRows || 1000)) return
    rows.value.push({ recordId: crypto.randomUUID(), stationName: '', ipAddress: '', deviceName: '', deviceModel: '', serialNumber: '', location: '', lastObservation: null })
  }
  function removeRow(row) {
    if (busy.value || !window.confirm('删除这条台账草稿记录？保存后才会更新磁盘台账。')) return
    rows.value = rows.value.filter(item => item.recordId !== row.recordId)
    selected.value = selected.value.filter(id => id !== row.recordId)
  }
  const save = () => busy.value || !loaded.value ? undefined : perform(async () => {
    const result = await api.save({ expectedRevision: revision.value, records: copy(rows.value) }, signal)
    if (!disposed) { revision.value = result.revision; baseline.value = JSON.stringify(result.records); notice.value = '台账已保存到项目数据目录' }
  })
  async function reload() {
    if (busy.value || (dirty.value && !window.confirm('重新加载会丢弃当前未保存草稿，是否继续？'))) return
    await perform(async () => { const result = await api.ledger(signal); if (!disposed) { acceptLedger(result); selected.value = []; notice.value = '已重新加载台账' } })
  }
  async function query(row) {
    if (!canQuery.value) return
    const items = (row ? [row] : rows.value).map(item => ({ recordId: item.recordId, ipAddress: item.ipAddress.trim() }))
    if (!items.length) return
    await perform(async () => {
      const job = await api.submit({ requestKey: crypto.randomUUID(), items }, signal)
      if (!disposed) { applied.clear(); apply(job) }
    })
  }
  const cancel = () => !running.value ? undefined : perform(async () => { const job = await api.cancel(task.value.taskId, signal); if (!disposed) apply(job) })
  function rowResult(row) { return task.value?.results?.find(item => item.recordId === row.recordId && item.ipAddress === row.ipAddress.trim()) }
  function observation(row) { return row.lastObservation?.ipAddress === row.ipAddress.trim() ? row.lastObservation : null }
  function stateText(row) {
    const result = rowResult(row)
    if (result) return ({ MAPPING_FOUND: '已观察映射', CACHED_MAPPING: '缓存映射', QUERY_CANCELLED: '已取消', QUERY_TIMEOUT: '超时', SKIPPED: '已跳过' })[result.status] || result.message
    if (running.value) return '任务执行中'
    return observation(row) ? '历史观察值' : '未查询'
  }
  async function copyRow(row, macOnly = false) {
    await perform(async () => {
      const o = observation(row)
      if (macOnly && !o) throw { message: '没有可复制的 MAC 地址', code: 'NO_MAC' }
      const text = macOnly ? o.macAddress : [row.stationName, row.ipAddress, row.deviceName, row.deviceModel, row.serialNumber, o?.macAddress || '', row.location].join('\t')
      await navigator.clipboard.writeText(text); notice.value = '已复制'
    })
  }
  async function download() {
    if (busy.value || !loaded.value) return
    const records = copy(exportScope.value === 'selected' ? rows.value.filter(row => selected.value.includes(row.recordId)) : rows.value)
    if (!records.length) { error.value = { message: '请选择需要导出的记录', code: 'NO_EXPORT_DATA' }; return }
    await perform(async () => {
      const result = await api.download({ format: format.value, columns: [...columns.value], records }, signal)
      if (disposed) return
      const url = URL.createObjectURL(result.blob), anchor = document.createElement('a')
      anchor.href = url; anchor.download = result.filename; document.body.appendChild(anchor); anchor.click(); anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000); exportOpen.value = false; notice.value = '导出文件已生成'
    })
  }
  onMounted(refresh)
  onUnmounted(() => { disposed = true; clearTimeout(timer); controller.abort() })
  return { rows, task, capabilities, error, notice, pending, loaded, available, selected, dirty, running, busy, canQuery,
    exportOpen, format, exportScope, columns, refresh, addRow, removeRow, save, reload, query, cancel, copyRow, download, observation, stateText, rowResult }
}
