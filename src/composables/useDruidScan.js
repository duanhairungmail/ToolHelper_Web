import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { druidScanApi } from '../api/druidScan.js'

export const druidColumns = [['index', '序号'], ['target', '目标'], ['retest', '历史漏洞复测结论'], ['identification', '识别结果'], ['accessStatus', '访问状态'],
  ['severity', '规则风险'], ['executionStatus', '执行状态'], ['progress', '完成规则'], ['credentialStatus', '凭据验证'],
  ['elapsedMs', '耗时ms'], ['checkedAt', '检测时间'], ['ruleVersion', '规则版本']]
const labels = {
  IDENTIFIED: '已识别', UNIDENTIFIED: '未识别', ANONYMOUS_ACCESS: '匿名可访问', ANONYMOUS_JSON: '匿名可访问', AUTH_REQUIRED: '当前端点需登录',
  ACCESS_RESTRICTED: '当前来源受限', PAGE_ONLY: '仅识别页面', PAGE_IDENTIFIED: '仅识别页面', UNKNOWN: '无法判断', UNKNOWN_RESPONSE: '无法判断',
  BUSINESS_AUTH_REQUIRED: '业务登录拦截，Druid鉴权未验证',
  HIGH: '高', MEDIUM: '中', INFO: '提示', UNRATED: '未定级', QUEUED: '等待执行', RUNNING: '检测中', CANCELLING: '停止并清理中',
  COMPLETED: '处理结束', PARTIAL: '部分完成', CANCELLED: '已取消', FAILED: '任务失败', PATH_NOT_FOUND: '指定路径未发现',
  VERIFIED: '指定凭据验证通过', REJECTED: '指定凭据被拒绝', UNCONFIRMED: '登录后访问未确认', NOT_REQUESTED: '未请求', NOT_EXECUTED: '未执行',
  SKIPPED_NO_SUPPORTED_LOGIN: '无需或不支持本次凭据验证', UNSUPPORTED_RESPONSE: '不支持的登录响应',
  REQUEST_TIMEOUT: '请求超时', TARGET_TIMEOUT: '目标总超时', NETWORK_ERROR: '网络错误', DNS_ERROR: 'DNS解析失败', TLS_ERROR: 'TLS证书或握手失败',
  REDIRECT_RESTRICTED: '跳转受限', RESPONSE_TOO_LARGE: '响应超限', CONTENT_ENCODING_UNSUPPORTED: '不支持的响应压缩', RATE_LIMITED: '目标请求受限',
  REQUEST_LIMIT: '请求次数已达上限', AMBIGUOUS_RESPONSE: '通用响应，无法判断', HTTP_ERROR: 'HTTP异常', DIRECT: '直连'
}
export const druidLabel = value => labels[value] || value || '—'
const isActive = task => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(task?.status)
export function createDruidDraft() { return { mode: ref('single'), target: ref(''), batch: ref(''), viewId: ref('') } }
const sharedDraft = createDruidDraft()
export function useDruidScan(api = druidScanApi, draft = sharedDraft, confirm = message => window.confirm(message)) {
  const { mode, target, batch, viewId } = draft
  // 凭据只属于当前挂载，不共享、不写浏览器存储，发起时立即清空密码。
  const username = ref(''), password = ref(''), capabilities = ref(null), history = ref([]), active = ref(null), task = ref(null)
  const rows = ref([]), detail = ref(null), selected = ref([]), error = ref(null), notice = ref(''), pending = ref(false), available = ref(false)
  const exportOpen = ref(false), format = ref('xlsx'), scope = ref('all'), includeDetails = ref(true), columns = ref(druidColumns.map(c => c[0]))
  const running = computed(() => isActive(active.value)), busy = computed(() => running.value || pending.value)
  let disposed = false, refreshing = false, generation = 0, detailGeneration = 0, mutationVersion = 0, timer
  const controller = new AbortController(), signal = controller.signal
  async function loadView(id, ticket = ++generation) {
    if (!id) { task.value = null; rows.value = []; detail.value = null; return }
    const job = await api.task(id, signal), all = []
    for (let page = 1; page <= 2; page++) {
      const result = await api.results(id, page, signal); all.push(...result.items)
      if (all.length >= result.total) break
    }
    if (!disposed && ticket === generation && id === viewId.value) { task.value = job; rows.value = all }
  }
  async function refresh() {
    if (disposed || refreshing) return
    refreshing = true
    const version = mutationVersion
    try {
      const caps = await api.capabilities(signal), current = await api.active(signal), jobs = await api.history(signal)
      if (disposed || version !== mutationVersion) return
      capabilities.value = caps; active.value = current; history.value = jobs; available.value = true
      if (!jobs.some(job => job.taskId === viewId.value)) {
        viewId.value = current?.taskId || jobs[0]?.taskId || ''; detail.value = null; selected.value = []
      }
      await loadView(viewId.value)
    } catch (failure) { if (!disposed) { error.value = failure; available.value = false } }
    finally {
      refreshing = false; clearTimeout(timer)
      if (!disposed) timer = setTimeout(refresh, running.value ? 1000 : 5000)
    }
  }
  async function perform(action) {
    if (disposed || pending.value) return
    mutationVersion++; generation++; pending.value = true; error.value = null; notice.value = ''
    try { await action() } catch (failure) { if (!disposed) error.value = failure }
    finally { pending.value = false; if (!disposed) await refresh() }
  }
  async function choose(id) {
    detailGeneration++; viewId.value = id; detail.value = null; selected.value = []; rows.value = []
    try { await loadView(id) } catch (failure) { if (!disposed) error.value = failure }
  }
  async function start(credential = false) {
    if (busy.value || !available.value) return
    const targets = (mode.value === 'single' ? [target.value.trim()] : batch.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean))
    if (!targets.length || targets.some(value => !value) || targets.length > (capabilities.value?.limits.maxTargets || 100)) {
      error.value = { message: '请填写配额内的完整目标URL', code: 'INVALID_TARGETS' }; return
    }
    let body
    if (credential) {
      if (mode.value !== 'single' || !username.value.trim()) { error.value = { message: '凭据验证只支持单目标，请填写用户名', code: 'INVALID_CREDENTIAL_FIELD' }; return }
      let http
      try { http = new URL(targets[0]).protocol === 'http:' } catch { error.value = { message: '请填写完整http/https URL', code: 'INVALID_TARGETS' }; return }
      if (http && !confirm(`将向 ${targets[0]} 通过HTTP明文发送本次指定凭据，仅允许可信网络。是否确认本次验证？`)) return
      body = { requestKey: crypto.randomUUID(), target: targets[0], username: username.value, password: password.value, allowInsecureHttp: http }
      password.value = ''
    } else body = { requestKey: crypto.randomUUID(), targets }
    await perform(async () => {
      const job = await (credential ? api.credentials(body, signal) : api.submit(body, signal))
      if (!disposed) { active.value = job; task.value = job; viewId.value = job.taskId; detail.value = null; rows.value = []; selected.value = [] }
    })
    if (credential) { body.password = ''; body.username = '' }
  }
  async function showDetail(row) {
    const id = viewId.value, ticket = ++detailGeneration
    try { const result = await api.detail(id, row.targetId, signal); if (!disposed && viewId.value === id && ticket === detailGeneration) detail.value = result }
    catch (failure) { if (!disposed) error.value = failure }
  }
  const cancel = () => !running.value || pending.value ? undefined : perform(async () => { active.value = await api.cancel(active.value.taskId, signal) })
  const clear = () => busy.value || !confirm('清空本模块所有已结束检测结果？不会访问或重置目标，未下载的报告将无法恢复。') ? undefined : perform(async () => {
    await api.clear(signal); viewId.value = ''; task.value = null; rows.value = []; detail.value = null; selected.value = []; notice.value = '已清空本模块结果'
  })
  async function download() {
    if (busy.value || !rows.value.length) return
    const targetIds = scope.value === 'all' ? rows.value.map(r => r.targetId) : rows.value.filter(r => selected.value.includes(r.targetId)).map(r => r.targetId)
    if (!targetIds.length) { error.value = { message: '请先选择目标', code: 'NO_EXPORT_DATA' }; return }
    await perform(async () => {
      const result = await api.download({ taskId: viewId.value, targetIds, columns: [...columns.value], format: format.value, details: includeDetails.value }, signal)
      if (disposed) return
      const url = URL.createObjectURL(result.blob), anchor = document.createElement('a')
      anchor.href = url; anchor.download = result.filename; document.body.appendChild(anchor); anchor.click(); anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000); exportOpen.value = false; notice.value = '报告已生成'
    })
  }
  async function copySuggestions() {
    try { await navigator.clipboard.writeText((capabilities.value?.remediation || []).join('\n')); notice.value = '整改建议已复制' }
    catch { error.value = { message: '剪贴板不可用，可手动选择建议文本复制', code: 'CLIPBOARD_UNAVAILABLE' } }
  }
  watch(mode, () => { username.value = ''; password.value = '' })
  onMounted(refresh)
  onUnmounted(() => { disposed = true; generation++; clearTimeout(timer); controller.abort(); username.value = ''; password.value = '' })
  return { mode, target, batch, username, password, capabilities, history, active, task, viewId, rows, detail, selected, error, notice,
    pending, available, running, busy, exportOpen, format, scope, includeDetails, columns, refresh, choose, start, cancel, clear, showDetail, download, copySuggestions }
}
