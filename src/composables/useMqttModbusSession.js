import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { mqttModbusApi } from '../api/mqttModbus.js'

export function useMqttModbusSession(editor, api = mqttModbusApi) {
  const sessions = editor.runtimeSessions, busy = ref(false), error = ref(''), ready = ref(false)
  const username = ref(''), password = ref('')
  watch(editor.selectedGatewayKey, () => { username.value = ''; password.value = '' }, { flush: 'sync' })
  const cursors = new Map()
  const hydrated = new Set(sessions.value.map(s => s.sessionId))
  let disposed = false, timer, lastHeartbeat = 0, refreshTask, lastRefreshError
  const active = computed(() => sessions.value.find(s => s.gateway.key === editor.selectedGatewayKey.value))
  // 拓扑先停止再编辑；锁定整个草稿，避免删除网关迁移设备时涉及另一个运行会话。
  const topologyLocked = computed(() => !ready.value || busy.value || sessions.value.length > 0)
  const log = (kind, message) => {
    editor.logs.value.unshift({ time: new Date().toLocaleTimeString('zh-CN'), kind, message })
    editor.logs.value = editor.logs.value.slice(0, 30)
  }
  function restore(session) {
    let gateway = editor.gateways.value.find(g => g.key === session.gateway.key)
    if (!gateway) {
      gateway = { ...session.gateway, interval: '2000', qos: String(session.gateway.qos) }
      editor.gateways.value.push(gateway)
    } else if (!hydrated.has(session.sessionId)) {
      Object.assign(gateway, session.gateway, { qos: String(session.gateway.qos) })
    }
    if (hydrated.has(session.sessionId)) return
    // 刷新页面后恢复运行快照时，替换该网关的默认设备，避免出现重复地址。
    editor.devices.value = editor.devices.value.filter(d => d.gatewayKey !== session.gateway.key)
    for (const device of session.devices) {
      const existing = editor.devices.value.find(d => d.id === device.id)
      const ascii = (start, count) => device.inputRegisters.slice(start - 1, start - 1 + count).map(v => v ? String.fromCharCode(v) : '').join('')
      const restored = { ...device, inputRegisters: [...device.inputRegisters], holdingRegisters: [...device.holdingRegisters], coils: [...device.coils], serialNumber: ascii(23, 11), hardwareVersion: ascii(34, 14), softwareVersion: ascii(48, 16) }
      if (existing) Object.assign(existing, restored)
      else editor.devices.value.push(restored)
    }
    hydrated.add(session.sessionId)
  }
  async function readState() {
    try {
      const list = await api.list()
      if (disposed) return
      list.forEach(restore)
      sessions.value = list
      ready.value = true
      for (const session of list) {
        const result = await api.events(session.sessionId, cursors.get(session.sessionId) || 0)
        if (disposed) return
        if (result.gap) log('系统', `${session.gateway.name} 的旧事件已过期，已读取当前事件窗口。`)
        result.events.forEach(event => log(event.kind, `${session.gateway.name} · ${event.topic} · ${event.result}${event.hex ? ' · ' + event.hex : ''}`))
        cursors.set(session.sessionId, result.cursor)
      }
      if (Date.now() - lastHeartbeat >= 15000) {
        // 心跳独立于本地预览，网络结果不明时不重放写请求。
        lastHeartbeat = Date.now()
        for (const session of list) { if (disposed) return; await api.heartbeat(session.sessionId) }
      }
      if (error.value === lastRefreshError) error.value = ''
      lastRefreshError = undefined
    } catch (e) { if (!disposed) { ready.value = false; lastRefreshError = e.message; error.value = e.message } }
  }
  function refresh() {
    if (disposed || busy.value) return Promise.resolve()
    if (!refreshTask) refreshTask = readState().finally(() => { refreshTask = undefined })
    return refreshTask
  }
  const devicePayload = key => editor.devices.value.filter(d => d.gatewayKey === key).map(d => ({
    id: d.id, gatewayKey: d.gatewayKey, name: d.name, modelId: d.modelId, address: Number(d.address),
    inputRegisters: [...d.inputRegisters], holdingRegisters: [...d.holdingRegisters], coils: [...d.coils]
  }))
  async function mutate(operation) {
    if (busy.value || disposed) return
    busy.value = true
    try {
      // 等当前读取结束后再提交写入，防止旧 GET 覆盖刚创建的会话。
      if (refreshTask) await refreshTask
      if (disposed) return
      await operation(); error.value = ''
    }
    catch (e) { error.value = e.message; log('异常', e.message) }
    finally { busy.value = false; await refresh() }
  }
  async function connect() {
    if (!ready.value || active.value) return
    const g = editor.activeGateway.value
    await mutate(async () => {
      const { key, name, gatewayId, brokerUrl, clientId, downTopic, upTopic } = g
      try {
        const session = await api.open({ gateway: { key, name, gatewayId, brokerUrl, clientId: clientId || null, qos: Number(g.qos), downTopic: downTopic || null, upTopic: upTopic || null }, devices: devicePayload(key), username: username.value, password: password.value })
        hydrated.add(session.sessionId)
        sessions.value.push(session)
        log('连接', `${g.name} 已订阅 ${session.gateway.downTopic}，等待查询。`)
      } finally { password.value = '' }
    })
  }
  async function stop() {
    const session = active.value
    if (session) await mutate(() => api.close(session.sessionId))
  }
  async function applyProfile() {
    const session = active.value
    if (session) await mutate(async () => {
      const updated = await api.profile(session.sessionId, { expectedVersion: session.version, devices: devicePayload(session.gateway.key) })
      sessions.value = sessions.value.map(s => s.sessionId === updated.sessionId ? updated : s)
      log('配置', `运行数据已更新到版本 ${updated.version}。`)
    })
  }
  onMounted(() => { refresh(); timer = setInterval(refresh, 2000) })
  onBeforeUnmount(() => {
    disposed = true; clearInterval(timer); password.value = ''; username.value = ''
    // 离开停止续租；后端 60 秒内回收，返回页面可在到期前恢复。
  })
  return { sessions, busy, error, ready, username, password, active, topologyLocked, connect, stop, applyProfile, refresh }
}
