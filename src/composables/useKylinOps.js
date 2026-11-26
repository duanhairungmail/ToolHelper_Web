import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { kylinOpsApi } from '../api/kylinOps.js'

const runningStates = ['QUEUED', 'RUNNING', 'CANCELLING']
export const kylinLabel = value => ({ ACTIVATED: '已激活', NOT_ACTIVATED: '未激活', UNKNOWN: '无法判断',
  NOT_APPLICABLE: '不适用', MISSING: '缺失', PRESENT: '存在', OBSERVED: '已观察', READ_ONLY: '只读', SELECTABLE: '可选', COMPLIANT: '符合停用策略', NOT_COMPLIANT: '尚未符合停用策略',
  BASELINE_UNVERIFIED: '厂商漏洞基线待核实', RESOURCE_MISSING: '依据或资源缺失', QUEUED: '等待执行', RUNNING: '执行中',
  PATCH_REQUIRED: '低于提供的补丁版本，待修复', PATCH_VERSION_REACHED: '已达到提供的补丁版本', NOT_INSTALLED: '组件未安装', PACKAGE_UNHEALTHY: '组件安装状态异常', USER_PROVIDED_PATCH: '用户提供的补丁',
  PATCH_VERIFIED: '补丁版本及运行状态验证通过', PATCH_NOT_VERIFIED: '补丁验证未通过，需核对',
  SUCCEEDED: '验证通过', FAILED: '失败', PARTIAL: '部分生效，需核对', CANCELLED: '已取消', CANCELLING: '正在停止后续步骤',
  UNKNOWN_REMOTE_STATE: '远程状态待核对', DEPLOY: '部署', UNINSTALL: '卸载', RESTORE: '恢复备份', START: '启动服务', STOP: '停止服务',
  CLEAN_NOW: '立即清理（不可恢复）', RELOAD: '重新加载配置', APPLY: '应用选中优化', PRECHECK: '预检', BACKUP: '备份',
  VALIDATING: '语法与完整性检查', APPLYING: '应用策略', VERIFYING: '后置验证', UPLOADING: '上传补丁', INSTALLING: '安装补丁', REPAIR: '修复', TEMP_CLEANUP_FAILED: '临时目录清理失败',
  ACTIVATE: '密钥激活', GETTING_QR: '获取二维码', WAITING_CODE: '等待微信扫码及激活码', SUBMITTING_CODE: '已提交激活码', ACTIVATING: '正在激活', COMPLETED: '已完成' }[value] || value)

export function useKylinOps(api = kylinOpsApi, confirm = message => window.confirm(message)) {
  const caps = ref(null), connection = ref(null), key = ref(null), feature = ref('activation'), action = ref('DEPLOY')
  const host = ref(''), port = ref(22), username = ref(''), password = ref(''), sudoPassword = ref(''), vncPassword = ref('')
  const params = ref({ desktopUser: '', schedule: '0 0 1 * *', journalDays: 30, fileDays: 365,
    listenAddress: '127.0.0.1', port: 5901, allowedCidr: '', optimizationIds: [], backupId: '' })
  const scan = ref(null), plan = ref(null), task = ref(null), backups = ref([]), error = ref(null), pending = ref(false), notice = ref('')
  const confirmed = ref(false)
  const sudoRemembered = ref(false)
  const activationTask = ref(null), activationDialog = ref(''), activationQrUrl = ref('')
  const activationForm = ref({ stationName: '', serialNumber: '', activationKey: '', activationCode: '' })
  const activationRecord = ref(null), activationUncertain = ref(false), codeSubmitted = ref(false)
  const activationDraft = ref(false)
  const activationRunning = computed(() => runningStates.includes(activationTask.value?.status))
  const running = computed(() => runningStates.includes(task.value?.status) || activationRunning.value || activationUncertain.value)
  const busy = computed(() => pending.value || running.value)
  const currentFeature = computed(() => caps.value?.features.find(item => item.id === feature.value))
  let disposed = false, timer, planTimer, generation = 0, planPassword = ''
  let rememberedPassword = '', sudoTimer, credentialEpoch = 0, activationCredential = null
  const clearSudo = () => {
    rememberedPassword = ''; sudoRemembered.value = false; sudoPassword.value = ''; planPassword = ''; activationCredential = null
    clearTimeout(sudoTimer); credentialEpoch++
  }
  // 仅在组件闭包内绑定当前连接，不写入浏览器存储；后台轮询不能延长保存时间。
  const credentialContext = () => ({ id: connection.value?.connectionId, epoch: credentialEpoch })
  const operationPassword = () => sudoPassword.value || rememberedPassword
  function rememberSudo(value, context) {
    if (disposed || !value || context.epoch !== credentialEpoch || context.id !== connection.value?.connectionId || connection.value?.system?.uid === '0') return
    rememberedPassword = value; sudoRemembered.value = true; clearTimeout(sudoTimer)
    sudoTimer = setTimeout(clearSudo, 15 * 60 * 1000)
  }
  function clearRejectedSudo(failure) {
    if (failure?.code?.startsWith('KYLIN_SUDO_') || failure?.code === 'SSH_DISCONNECTED' || failure?.code === 'SSH_HOST_KEY_CHANGED') clearSudo()
  }
  const closePlan = () => { plan.value = null; confirmed.value = false; planPassword = ''; vncPassword.value = ''; clearTimeout(planTimer) }
  const controller = new AbortController(), signal = controller.signal
  let qrTaskId = '', qrLoading = false
  const revokeQr = () => { if (activationQrUrl.value) URL.revokeObjectURL(activationQrUrl.value); activationQrUrl.value = ''; qrTaskId = '' }
  const clearActivation = () => { revokeQr(); activationTask.value = null; activationDialog.value = ''; activationRecord.value = null; activationUncertain.value = false; activationDraft.value = false; codeSubmitted.value = false; activationCredential = null; activationForm.value = { stationName: '', serialNumber: '', activationKey: '', activationCode: '' } }
  async function perform(work) {
    if (disposed || pending.value) return
    pending.value = true; error.value = null; notice.value = ''
    try { await work() } catch (failure) { if (!disposed) { clearRejectedSudo(failure); error.value = failure } }
    finally { if (!disposed) pending.value = false }
  }
  async function refresh() {
    const ticket = ++generation
    try {
      const [capabilities, current, active, activation] = await Promise.all([api.capabilities(signal), api.current(signal), api.active(signal), api.activationCurrent?.(signal)])
      if (disposed || ticket !== generation) return
      // 后端忙碌时可能返回 connected=false 的连接快照；把它当作已断开，
      // 否则页面仍隐藏主机输入框和探测/连接按钮，用户只能刷新才能恢复。
      const liveConnection = current?.connected === false ? null : current
      caps.value = capabilities; connection.value = liveConnection
      if (activation && liveConnection && activation.connectionId === liveConnection.connectionId) {
        activationTask.value = activation; activationUncertain.value = false
        if (activation.scan && feature.value === 'activation' && (!scan.value || Date.parse(activation.scan.checkedAt) > Date.parse(scan.value.checkedAt))) scan.value = activation.scan
        if (activationCredential && activation.scan?.findings?.[0]?.status !== 'UNKNOWN' && activation.scan) {
          rememberSudo(activationCredential.password, activationCredential.context); activationCredential = null
        }
        if (activation.code) { clearRejectedSudo({ code: activation.code }); activationCredential = null }
        if (!runningStates.includes(activation.status)) activationCredential = null
        if (activation.qrReady && activation.step === 'WAITING_CODE' && !activationQrUrl.value && !qrLoading) {
          qrLoading = true
          try {
            const blob = await api.activationQr(activation.taskId, signal)
            if (!disposed && ticket === generation && connection.value?.connectionId === activation.connectionId && activationTask.value?.taskId === activation.taskId && activationTask.value.qrReady) {
              revokeQr(); activationQrUrl.value = URL.createObjectURL(blob); qrTaskId = activation.taskId
            }
          } finally { qrLoading = false }
        }
        if (!activation.qrReady || (qrTaskId && qrTaskId !== activation.taskId)) revokeQr()
      } else if (activationUncertain.value) { activationUncertain.value = false; activationCredential = null; notice.value = '服务中没有待执行激活任务，请核对状态后再操作' }
      if (active) task.value = active
      else if (task.value && runningStates.includes(task.value.status)) {
        const completed = await api.task(task.value.taskId, signal)
        if (disposed || ticket !== generation) return
        task.value = completed
      }
      if (liveConnection && !runningStates.includes(task.value?.status)) {
        const records = await api.backups(liveConnection.connectionId, signal)
        if (disposed || ticket !== generation) return
        backups.value = records
      }
      if (!liveConnection) { plan.value = null; scan.value = null; backups.value = []; clearActivation(); if (task.value?.feature === 'activation') task.value = null }
    } catch (failure) { if (!disposed && ticket === generation) { clearRejectedSudo(failure); error.value = failure } }
    finally { if (!disposed && ticket === generation) { clearTimeout(timer); timer = setTimeout(refresh, running.value ? 1000 : 10000) } }
  }
  const probe = () => perform(async () => { key.value = await api.probe({ host: host.value.trim(), port: Number(port.value) }, signal) })
  const trust = () => perform(async () => {
    const current = key.value
    if (!current || !confirm(`请通过可信渠道核对 ${current.host}:${current.port} 的主机指纹：\n${current.fingerprint}\n确认保存该主机信任？`)) return
    key.value = await api.trust({ host: current.host, port: current.port, fingerprint: current.fingerprint, confirmed: true }, signal)
  })
  async function connect() {
    if (busy.value || disposed) return
    const body = { host: host.value.trim(), port: Number(port.value), username: username.value.trim(), password: password.value }
    password.value = ''
    await perform(async () => { const result = await api.connect(body, signal); if (!disposed) { connection.value = result; notice.value = 'SSH已连接；不会自动应用策略' } })
    body.password = ''
  }
  const disconnect = () => busy.value ? undefined : perform(async () => {
    clearSudo()
    await api.disconnect(connection.value.connectionId, signal); connection.value = null; scan.value = null; plan.value = null; backups.value = []
    sudoPassword.value = ''; vncPassword.value = ''
  })
  async function inspect() {
    if (busy.value || !connection.value) return
    const context = credentialContext()
    const body = { connectionId: connection.value.connectionId, feature: feature.value, sudoPassword: operationPassword() }
    await perform(async () => {
      const result = await api.scan(body, signal)
      if (!disposed) {
        if (body.feature !== 'patch' && (body.feature !== 'activation' || result.findings?.[0]?.status !== 'UNKNOWN')) rememberSudo(body.sudoPassword, context)
        scan.value = result; plan.value = null
      }
    })
    body.sudoPassword = ''; sudoPassword.value = ''
  }
  async function preview() {
    if (busy.value || !connection.value) return
    closePlan()
    const context = credentialContext()
    const body = { connectionId: connection.value.connectionId, feature: feature.value, action: action.value,
      params: { ...params.value, optimizationIds: [...params.value.optimizationIds] }, sudoPassword: operationPassword() }
    await perform(async () => {
      const result = await api.plan(body, signal)
      if (!disposed) {
        rememberSudo(body.sudoPassword, context)
        // 计划内凭据与连接内缓存分开清理，取消计划不会清除已验证的连接凭据。
        planPassword = body.sudoPassword; plan.value = result; confirmed.value = false
        if (result.expiresAt) planTimer = setTimeout(() => { closePlan(); notice.value = '确认已超时，请重新点击操作按钮' }, Math.max(0, Date.parse(result.expiresAt) - Date.now()))
      }
    })
    body.sudoPassword = ''; sudoPassword.value = ''
  }
  async function beginAction(selected) {
    if (busy.value || !connection.value || !currentFeature.value?.actions.includes(selected)) return
    closePlan(); action.value = selected
    // 等待参数监听完成，避免刚生成的计划被同一次按钮操作的action变化清空。
    await nextTick()
    await preview()
  }
  async function approve() {
    if (busy.value || !plan.value) return
    confirmed.value = true
    await execute()
  }
  async function execute() {
    if (busy.value || !plan.value || !confirmed.value) return
    const body = { planId: plan.value.planId, confirmed: true, sudoPassword: sudoPassword.value || planPassword || rememberedPassword, vncPassword: vncPassword.value }
    sudoPassword.value = ''; vncPassword.value = ''
    await perform(async () => {
      // 请求发出即丢弃一次性计划；网络失败只查询active，不能重新执行原POST。
      closePlan()
      try { const result = await api.execute(body, signal); if (!disposed) task.value = result }
      finally { body.sudoPassword = ''; body.vncPassword = ''; if (!disposed) await refresh() }
    })
  }
  const cancel = () => activationRunning.value ? cancelActivation()
    : !runningStates.includes(task.value?.status) ? undefined : perform(async () => { task.value = await api.cancel(task.value.taskId, signal) })
  async function openActivation() {
    if (busy.value || !connection.value) return
    await inspect()
    if (error.value || scan.value?.findings?.[0]?.status !== 'NOT_ACTIVATED') { if (!error.value) notice.value = '只有明确检测为未激活的主机才能密钥激活；续期或取消激活请手动操作'; return }
    activationForm.value = { stationName: '', serialNumber: scan.value.activation?.serialNumber || '', activationKey: '', activationCode: '', ...activationRecord.value }
    activationForm.value.activationCode = ''; codeSubmitted.value = false; activationDraft.value = true; activationDialog.value = 'activate'
  }
  function openActivationExport() {
    if (busy.value || !scan.value || !connection.value) return
    activationForm.value = { stationName: '', serialNumber: scan.value.activation?.serialNumber || '', activationKey: '', activationCode: '', ...activationRecord.value }
    activationDialog.value = 'export'
  }
  function closeActivation() {
    if (activationRunning.value || pending.value || activationUncertain.value) return
    activationDialog.value = ''; activationForm.value = { stationName: '', serialNumber: '', activationKey: '', activationCode: '' }; revokeQr()
  }
  function validateActivation(required) {
    for (const [field, title, limit] of [['stationName', '变电站名称', 128], ['serialNumber', '服务序列号', 128], ['activationKey', '激活密钥', 512], ['activationCode', '激活码', 512]]) {
      const value = activationForm.value[field]
      if ((required.includes(field) && !value.trim()) || value.length > limit || /[\u0000-\u001f\u007f-\u009f]/.test(value)) throw { code: 'INVALID_ACTIVATION_FIELD', message: `${title}为空、过长或含控制字符` }
    }
  }
  async function startActivation() {
    if (busy.value || !connection.value || activationDialog.value !== 'activate') return
    const context = credentialContext()
    const body = { connectionId: connection.value.connectionId, serialNumber: activationForm.value.serialNumber, confirmed: true, serialDifferenceConfirmed: false, sudoPassword: operationPassword() }
    await perform(async () => {
      validateActivation(['stationName', 'serialNumber', 'activationKey'])
      const actual = scan.value?.activation?.serialNumber
      if (actual && actual !== body.serialNumber) {
        if (!confirm(`目标KEY为 ${actual}，输入服务序列号为 ${body.serialNumber}。确认按输入值继续激活？`)) return
        body.serialDifferenceConfirmed = true
      }
      // 发出请求后只查询原任务，响应丢失不能再次POST。
      activationDraft.value = false; activationUncertain.value = true; activationRecord.value = { ...activationForm.value }
      activationCredential = { password: body.sudoPassword, context }
      try {
        const result = await api.activate(body, signal)
        if (disposed || context.id !== connection.value?.connectionId) return
        activationTask.value = result
        activationUncertain.value = false
      } finally { await refresh() }
    })
    body.sudoPassword = ''; sudoPassword.value = ''
  }
  async function submitActivationCode() {
    if (pending.value || disposed || codeSubmitted.value || activationTask.value?.step !== 'WAITING_CODE' || !activationQrUrl.value) return
    await perform(async () => {
      validateActivation(['activationCode'])
      codeSubmitted.value = true
      const body = { activationCode: activationForm.value.activationCode }
      activationRecord.value = { ...activationRecord.value, activationCode: body.activationCode }
      try { const result = await api.continueActivation(activationTask.value.taskId, body, signal); if (!disposed) activationTask.value = result }
      finally { body.activationCode = ''; await refresh() }
    })
  }
  const cancelActivation = () => pending.value || !activationTask.value ? undefined : perform(async () => {
    activationTask.value = await api.cancelActivation(activationTask.value.taskId, signal); revokeQr(); await refresh()
  })
  async function exportActivation() {
    if (busy.value || !scan.value || !connection.value) return
    await perform(async () => {
      validateActivation(['stationName', 'serialNumber'])
      const actual = scan.value.activation?.serialNumber
      if (actual && actual !== activationForm.value.serialNumber && !confirm('导出序列号与当前目标KEY不同，确认按填写内容导出？')) return
      if (!confirm('导出文件包含明文激活密钥和激活码，请妥善保管。是否继续？')) return
      const record = { ...activationForm.value }
      const blob = await api.activationExport(record, signal)
      if (disposed) return
      activationRecord.value = record
      const url = URL.createObjectURL(blob), link = document.createElement('a')
      link.href = url; link.download = `KylinOS激活信息_${new Date().toISOString().replace(/[:.]/g, '-')}.xlsx`; link.click(); URL.revokeObjectURL(url)
      notice.value = '激活信息已导出，文件包含明文密钥和激活码'
    })
    if (!error.value) closeActivation()
  }
  watch(() => connection.value?.connected === false ? null : connection.value?.connectionId, () => { clearSudo(); clearActivation() }, { flush: 'sync' })
  watch(() => task.value?.code, code => clearRejectedSudo({ code }), { flush: 'sync' })
  watch([host, port], () => { key.value = null; password.value = '' })
  watch(feature, value => {
    scan.value = null; plan.value = null; confirmed.value = false; sudoPassword.value = ''; vncPassword.value = ''
    action.value = caps.value?.features.find(item => item.id === value)?.actions[0] || ''
    params.value.schedule = value === 'logs' ? '0 1 1 * *' : '0 0 1 * *'
  })
  watch([action, params], closePlan, { deep: true })
  watch(plan, value => { if (!value) { planPassword = ''; clearTimeout(planTimer) } }, { flush: 'sync' })
  onMounted(refresh)
  onUnmounted(() => {
    if (activationRunning.value && activationTask.value) api.cancelActivation(activationTask.value.taskId).catch(() => console.warn('离开页面时激活任务取消结果未确认，服务端超时后停止等待'))
    disposed = true; generation++; clearTimeout(timer); clearSudo(); closePlan(); clearActivation(); controller.abort(); password.value = ''; vncPassword.value = ''
  })
  return { caps, connection, key, feature, action, host, port, username, password, sudoPassword, vncPassword, params,
    scan, plan, task, backups, error, pending, notice, confirmed, running, busy, currentFeature, refresh, probe, trust,
    connect, disconnect, inspect, preview, beginAction, approve, closePlan, execute, cancel, sudoRemembered, clearSudo,
    activationTask, activationDialog, activationQrUrl, activationForm, activationRecord, activationRunning, activationUncertain, activationDraft, codeSubmitted,
    openActivation, openActivationExport, closeActivation, startActivation, submitActivationCode, cancelActivation, exportActivation }
}
