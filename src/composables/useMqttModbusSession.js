import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { meterPayload, METER_DOWN, METER_UP } from '../utils/simulatedMeter.js'
import { mqttModbusApi } from '../api/mqttModbus.js'

export function useMqttModbusSession(editor, api = mqttModbusApi) {
  const sessions = editor.runtimeSessions, busy = ref(false), error = ref(''), ready = ref(false)
  const username = ref(''), password = ref(''), meterSupported = ref(false)
  let capabilitiesLoaded = false
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
    // 电表固定主题属于运行路由，不覆盖网关原有的探测器主题草稿。
    const restoredGateway = { ...session.gateway, qos: String(session.gateway.qos) }
    if (session.kind === 'meter') { delete restoredGateway.downTopic; delete restoredGateway.upTopic }
    let gateway = editor.gateways.value.find(g => g.key === session.gateway.key)
    if (!gateway) {
      gateway = { downTopic: '', upTopic: '', ...restoredGateway, interval: '2000' }
      editor.gateways.value.push(gateway)
    } else if (!hydrated.has(session.sessionId)) {
      Object.assign(gateway, restoredGateway)
    }
    if (hydrated.has(session.sessionId)) return
    if (session.kind === 'meter') {
      // 服务端运行快照是恢复依据；仅首次恢复，后续轮询不得覆盖未应用草稿。
      editor.devices.value.filter(d => d.gatewayKey === session.gateway.key).forEach(d => { d.gatewayKey = '' })
      for (const type of Object.keys(editor.meterGatewayKeys.value)) {
        if (editor.meterGatewayKeys.value[type] === session.gateway.key) editor.meterGatewayKeys.value[type] = ''
      }
      for (const meter of session.meters) {
        const draft = editor.meters.value[meter.type]
        draft.id = meter.id; draft.encodingProfile = meter.encodingProfile
        if (meter.type === 'dc') draft.baudRateRaw = String(meter.baudRateRaw)
        for (const field of draft.fields) field.value = meter.measurements[field.key]
        editor.meterGatewayKeys.value[meter.type] = session.gateway.key
      }
      hydrated.add(session.sessionId)
      return
    }
    // 服务端已有探测器会话时以实际运行状态为准，清除与之冲突的本地电表绑定。
    for (const type of Object.keys(editor.meterGatewayKeys.value)) {
      if (editor.meterGatewayKeys.value[type] === session.gateway.key) {
        editor.meterGatewayKeys.value[type] = ''
        log('电表', `${gateway.name} 已恢复运行中的极早期设备会话，已解除本地电表绑定。`)
      }
    }
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
      if (!capabilitiesLoaded) {
        const capabilities = api.capabilities ? await api.capabilities() : {}
        meterSupported.value = capabilities.meter?.encodingProfile === 'firesys-legacy-v1'
        capabilitiesLoaded = true
      }
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
  const meterPayloads = key => Object.entries(editor.meterGatewayKeys.value)
    .filter(([, gatewayKey]) => gatewayKey === key)
    .map(([type]) => meterPayload(type, editor.meters.value[type], key))
  function meterDirty(key) {
    const session = sessions.value.find(s => s.gateway.key === key && s.kind === 'meter')
    if (!session) return false
    try {
      const drafts = meterPayloads(key)
      return drafts.some(m => {
        const applied = session.meters.find(a => a.id === m.id)
        return !applied || m.baudRateRaw !== (applied.baudRateRaw ?? undefined) ||
          Object.entries(m.measurements).some(([field, value]) => value !== applied.measurements[field])
      })
    } catch {
      // 非法草稿也属于未应用修改；具体字段错误由预览和提交校验展示。
      return true
    }
  }
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
    const meter = editor.gatewayMeterCount(g.key) > 0
    if (meter && !meterSupported.value) { error.value = '当前后端未声明支持电表，请更新 Java 服务。'; return }
    await mutate(async () => {
      const { key, name, gatewayId, brokerUrl, clientId, downTopic, upTopic } = g
      try {
        const session = await api.open({ gateway: { key, name, gatewayId, brokerUrl, clientId: clientId || null, qos: Number(g.qos), downTopic: meter ? METER_DOWN : downTopic || null, upTopic: meter ? METER_UP : upTopic || null }, kind: meter ? 'meter' : 'early', devices: meter ? [] : devicePayload(key), ...(meter ? { meters: meterPayloads(key) } : {}), username: username.value, password: password.value })
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
  async function applyProfile(gatewayKey) {
    const session = typeof gatewayKey === 'string' ? sessions.value.find(s => s.gateway.key === gatewayKey) : active.value
    if (session) await mutate(async () => {
      const updated = await api.profile(session.sessionId, { expectedVersion: session.version, ...(session.kind === 'meter' ? { meters: meterPayloads(session.gateway.key) } : { devices: devicePayload(session.gateway.key) }) })
      sessions.value = sessions.value.map(s => s.sessionId === updated.sessionId ? updated : s)
      log('配置', `运行数据已更新到版本 ${updated.version}。`)
    })
  }
  onMounted(() => { refresh(); timer = setInterval(refresh, 2000) })
  onBeforeUnmount(() => {
    disposed = true; clearInterval(timer); password.value = ''; username.value = ''
    // 离开停止续租；后端 60 秒内回收，返回页面可在到期前恢复。
  })
  return { meterSupported, meterDirty, sessions, busy, error, ready, username, password, active, topologyLocked, connect, stop, applyProfile, refresh }
}
