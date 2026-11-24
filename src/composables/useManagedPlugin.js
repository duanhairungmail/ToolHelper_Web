import { computed, onMounted, onUnmounted, ref } from 'vue'

const terminal = (task) => ['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(task?.status)
const owner = (task) => task?.pluginId || 'electerm'

export function useManagedPlugin({ append, setStatus }, api, { pluginId, name }) {
  const snapshot = ref(null)
  const available = ref(false)
  const checking = ref(true)
  const requesting = ref(false)
  const task = ref(null)
  const error = ref(null)
  const controller = new AbortController()
  let disposed = false
  let polling = false
  let timer
  let reportedError = ''
  const reportedTasks = new Set()
  const busy = computed(() => requesting.value || Boolean(snapshot.value?.busy) || Boolean(task.value && !terminal(task.value)))
  const installed = computed(() => snapshot.value?.state === 'INSTALLED')
  const usable = computed(() => available.value && snapshot.value?.platformSupported && !busy.value)
  const badge = computed(() => checking.value ? '正在连接本机服务' : !available.value ? '本机服务不可用'
    : busy.value ? '任务执行中' : ({ INSTALLED: '已安装', NOT_INSTALLED: '未安装', BROKEN: '安装损坏', RECOVERY_REQUIRED: '需要恢复' })[snapshot.value?.state] || '状态未知')

  function report(failure) {
    if (disposed) return
    error.value = failure
    const message = `${failure.message} [${failure.code || 'OPERATION_FAILED'}]${failure.requestId ? ` 请求标识：${failure.requestId}` : ''}`
    if (message !== reportedError) append(message)
    reportedError = message
    setStatus(failure.message, 'error')
  }

  function finishTask(value) {
    if (reportedTasks.has(value.taskId)) return
    reportedTasks.add(value.taskId)
    if (value.status !== 'SUCCEEDED') {
      report({ ...value.error, message: value.error?.message || value.message })
      return
    }
    showResult(value.result)
  }

  function showResult(result) {
    if (!result || disposed) return
    const message = result.message || result.status
    append(message)
    setStatus(message, 'success')
    for (const field of ['preservedDirectory', 'backupDirectory', 'cleanupDirectory']) {
      if (result[field]) append(`${({ preservedDirectory: '用户数据已保留', backupDirectory: '旧版本备份', cleanupDirectory: '待清理目录' })[field]}：${result[field]}`)
    }
  }

  // 使用串行轮询避免慢请求叠加；刷新页面后从 currentTask 接续，不重新创建任务。
  async function refresh() {
    if (disposed || polling) return
    clearTimeout(timer)
    polling = true
    try {
      const value = await api.status(controller.signal)
      if (disposed) return
      snapshot.value = value
      available.value = true
      if (['SERVICE_UNAVAILABLE', 'INVALID_RESPONSE', 'SESSION_REQUIRED'].includes(error.value?.code)) error.value = null
      reportedError = ''
      if (value.currentTask && owner(value.currentTask) === pluginId) task.value = value.currentTask
      else if (task.value && !terminal(task.value)) {
        try {
          task.value = await api.task(task.value.taskId, controller.signal)
        } catch (failure) {
          if (failure.status === 404) task.value = null
          throw failure
        }
      }
      if (terminal(task.value) && owner(task.value) === pluginId) finishTask(task.value)
    } catch (failure) {
      if (failure.code !== 'TASK_NOT_FOUND') available.value = false
      report(failure)
    } finally {
      checking.value = false
      polling = false
      if (!disposed) timer = setTimeout(refresh, busy.value ? 1000 : 4000)
    }
  }

  async function perform(operation, body = {}) {
    if (busy.value || disposed) return
    requesting.value = true
    error.value = null
    try {
      if (operation === 'install' || operation === 'update') {
        const selection = await api.checkUpdate(controller.signal)
        if (disposed) return
        if (!selection.compatible) throw { code: 'PLUGIN_VERSION_UNVERIFIED', message: `官方版本 ${selection.latestVersion} 尚未完成适配，请保留当前安装` }
        if (operation === 'update' && selection.result !== 'AVAILABLE') {
          showResult({ message: selection.result === 'UP_TO_DATE' ? '当前已是官方最新版本' : '官方版本低于当前版本，不执行降级' })
          return
        }
        if (!window.confirm(`${operation === 'install' ? '下载并安装' : '更新为'} ${name} ${selection.latestVersion}？${selection.packageVersion ? `\n包内声明版本：${selection.packageVersion}` : ''}\n官方包约 ${(selection.size / 1024 / 1024).toFixed(1)} MiB。${operation === 'update' ? `\n请先关闭 ${name}；将保留便携用户数据及旧版本备份。` : ''}`)) return
        body = { releaseSelectionId: selection.releaseSelectionId, expectedInstallRevision: snapshot.value?.installRevision, confirmed: true }
      }
      if (operation === 'uninstall') {
        if (!window.confirm(`确认删除本服务管理的 ${name} 程序？\n将保留已识别的便携用户数据和旧版本备份，不影响其他安装。请先关闭所有 ${name} 窗口。`)) return
        body = { expectedInstallRevision: snapshot.value?.installRevision, confirmed: true }
      }
      const result = await api.mutate(operation, body, controller.signal)
      if (disposed) return
      if (result.taskId) {
        task.value = result
        append(`任务已提交：${result.taskId}`)
      } else showResult(result)
    } catch (failure) { report(failure) }
    finally {
      requesting.value = false
      if (!disposed) await refresh()
    }
  }

  onMounted(refresh)
  onUnmounted(() => { disposed = true; clearTimeout(timer); controller.abort() })
  return { snapshot, available, checking, requesting, task, error, busy, installed, usable, badge, refresh, perform }
}
