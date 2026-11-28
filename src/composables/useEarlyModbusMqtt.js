import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createMeterDrafts } from '../utils/simulatedMeter.js'
import { rtuFrameHex } from '../utils/earlyModbusFrame.js'

function createDevice(number, source, gatewayKey) {
  const id = `device-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`
  const inputRegisters = source ? [...source.inputRegisters] : Array(63).fill(0)
  if (!source) {
    inputRegisters.splice(0, 22, 120, 0, 0, 0, 200, 180, 150, 120, 100, 0, 0, 0, 0, 0, 2460, 4820, 328, 1, 0, 20, 26, 6)
    ;[...`FD-${String(number).padStart(3, '0')}`.padEnd(11)].forEach((char, index) => { inputRegisters[22 + index] = char.charCodeAt(0) })
    writeAsciiRegisters(inputRegisters, 0x22, 14, 'F2J1D4B3A12.16')
    writeAsciiRegisters(inputRegisters, 0x30, 16, 'WKZ_APP_V5.1.20')
  }
  return {
    id,
    gatewayKey,
    name: source ? `${source.name} 副本` : `${number}#极早期`,
    modelId: source ? `${source.modelId}-copy` : `FD-${String(number).padStart(3, '0')}`,
    serialNumber: source ? source.serialNumber : `FD-${String(number).padStart(3, '0')}`,
    hardwareVersion: source ? source.hardwareVersion : 'F2J1D4B3A12.16',
    softwareVersion: source ? source.softwareVersion : 'WKZ_APP_V5.1.20',
    address: number,
    inputRegisters,
    holdingRegisters: source ? [...source.holdingRegisters] : [20, 26, 6, 28, 10, 24, 0, 180, 10, 160, 10, 140, 10, 120, 10, 100, 10, 180, 10, 160, 10, 140, 10, 120, 10, 100, 10, 8, 0, 20, 0, number, 7, 2, 1, 1, 80, 0, 2],
    coils: source ? [...source.coils] : [true, true, false, false, true, false, false, false, false, true]
  }
}

function writeAsciiRegisters(registers, startAddress, count, value) {
  for (let index = 0; index < count; index += 1) {
    const code = value.charCodeAt(index)
    registers[startAddress - 1 + index] = Number.isFinite(code) ? code : 0
  }
}

function now() {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3, hour12: false }).format(new Date())
}

// 草稿只保留于模块内存，切换工具可恢复；刷新页面不恢复配置或运行状态。
export function createEarlyModbusMqttDraft() {
  const gateway = { key: 'gateway-1', name: '网关 1', gatewayId: 'gateway-001', brokerUrl: '', qos: '0', interval: '2000' }
  const device = createDevice(1, undefined, gateway.key)
  return {
    selectedTemplate: ref('normal'), dataTab: ref('input'), gateways: ref([gateway]),
    selectedGatewayKey: ref(gateway.key), devices: ref([device]), selectedDeviceId: ref(device.id),
    meterGatewayKeys: ref({ dc: '', ac: '' }), meters: ref(createMeterDrafts()),
    previewCount: ref(0), lastPreview: ref('尚未预览'), runtimeSessions: ref([]),
    logs: ref([{ time: now(), kind: '系统', message: '本地预览已就绪；真实收发请连接 MQTT 网关。' }])
  }
}
const sharedDraft = createEarlyModbusMqttDraft()

export function useEarlyModbusMqtt(draft = sharedDraft) {
  const { selectedTemplate, dataTab, gateways, selectedGatewayKey, devices, selectedDeviceId, meterGatewayKeys, meters, previewCount, lastPreview, logs, runtimeSessions } = draft
  const inputRegisterNames = [
    '烟雾实时曲线值', '故障类型位图', '报警级别', '设备工作状态',
    '当前阈值：火警 2', '当前阈值：火警 1', '当前阈值：巡警', '当前阈值：预警', '当前阈值：示警',
    '管道 1 状态', '管道 2 状态', '管道 3 状态', '管道 4 状态', '温度正负', '温度值 ×100', '湿度值 ×100', '电压值 ×100',
    '产品型号', '型号名称', '出厂年份（世纪）', '出厂年份', '出厂月份',
    ...Array.from({ length: 11 }, (_, index) => `序列号字符 ${index + 1}`),
    ...Array.from({ length: 14 }, (_, index) => `硬件版本字符 ${index + 1}`),
    ...Array.from({ length: 16 }, (_, index) => `软件版本字符 ${index + 1}`)
  ]

  const holdingRegisterNames = [
    '年份（世纪）', '年份', '月份', '日期', '小时', '分钟', '秒',
    '白天火警 2 阈值', '白天火警 2 延时', '白天火警 1 阈值', '白天火警 1 延时', '白天巡警阈值', '白天巡警延时', '白天预警阈值', '白天预警延时', '白天示警阈值', '白天示警延时',
    '晚上火警 2 阈值', '晚上火警 2 延时', '晚上火警 1 阈值', '晚上火警 1 延时', '晚上巡警阈值', '晚上巡警延时', '晚上预警阈值', '晚上预警延时', '晚上示警阈值', '晚上示警延时',
    '场景辅助白天小时', '场景辅助白天分钟', '场景辅助晚上小时', '场景辅助晚上分钟', '通讯地址', '通讯波特率', '气流级别', '气流检测', '设备声音', '采样能力', '测试', '应用场景设定'
  ]

  const coilNames = ['管道 1 开关', '管道 2 开关', '管道 3 开关', '管道 4 开关', '白天使能', '晚上使能', '复位', '静音', '场景辅助系统', '适应环境']
  const templates = { normal: '正常运行', alarm: '火警触发', fault: '设备故障' }
  const activeGateway = computed(() => gateways.value.find((gateway) => gateway.key === selectedGatewayKey.value) || gateways.value[0])
  const gatewayDevices = computed(() => devices.value.filter((device) => device.gatewayKey === selectedGatewayKey.value))
  const unboundDevices = computed(() => devices.value.filter((device) => !device.gatewayKey))
  // 电表网关不筛掉探测器草稿；查看设备与设备实际 MQTT 归属独立。
  const visibleDevices = computed(() => gatewayMeterCount(selectedGatewayKey.value)
    ? devices.value : [...gatewayDevices.value, ...unboundDevices.value])
  const activeDevice = computed(() => visibleDevices.value.find((device) => device.id === selectedDeviceId.value) || visibleDevices.value[0])
  // 通过设备的唯一绑定解析发送目标，切换界面不会改变设备的归属。
  const deviceGateway = computed(() => gateways.value.find((gateway) => gateway.key === activeDevice.value?.gatewayKey))
  const deviceDownTopic = computed(() => deviceGateway.value ? (deviceGateway.value.downTopic || `firetopic/${deviceGateway.value.gatewayId || '{gatewayId}'}/down`) : '')
  const deviceUpTopic = computed(() => deviceGateway.value ? (deviceGateway.value.upTopic || `firetopic/${deviceGateway.value.gatewayId || '{gatewayId}'}/up`) : '')
  const registerRows = computed(() => {
    if (!activeDevice.value) return []
    if (dataTab.value === 'input') return inputRegisterNames.map((name, index) => ({ address: index + 1, name, value: activeDevice.value.inputRegisters[index] }))
    if (dataTab.value === 'holding') return holdingRegisterNames.map((name, index) => ({ address: index + 1, name, value: activeDevice.value.holdingRegisters[index] }))
    return coilNames.map((name, index) => ({ address: index + 1, name, value: activeDevice.value.coils[index] }))
  })
  const alarmLabels = ['正常', '示警', '预警', '巡警', '火警 1', '火警 2']
  const faultOptions = ['气流堵塞', '气流漏气', '风扇故障', '显示屏通信中断', '系统故障 1', '系统故障 2', '系统故障 3', '系统故障 4', '系统故障 5', '系统故障 6', '系统故障 7', '系统故障 8', '系统故障 9', '系统故障 10', '温湿度传感器故障', '电压故障'].map((label, bit) => ({ bit, label }))
  const deviceAlarm = computed(() => alarmLabels[activeDevice.value.inputRegisters[2]] || `未知级别 (${activeDevice.value.inputRegisters[2]})`)
  const deviceSeverityClass = computed(() => activeDevice.value.inputRegisters[2] > 0 ? 'alarm' : activeDevice.value.inputRegisters[1] > 0 || activeDevice.value.inputRegisters[3] > 0 ? 'fault' : 'normal')
  const faultSummary = computed(() => faultOptions.filter(({ bit }) => isFaultSelected(bit)).map(({ label }) => label).join('、') || '无故障')
  const temperature = computed(() => `${activeDevice.value.inputRegisters[14] / 100 * (activeDevice.value.inputRegisters[13] ? -1 : 1)} °C`)
  const responsePreview = computed(() => activeDevice.value.inputRegisters.slice(0, 22).map((value) => Number(value || 0).toString(16).padStart(4, '0').toUpperCase()).join(' '))
  const addressHex = computed(() => Number(activeDevice.value.address || 0).toString(16).padStart(2, '0').toUpperCase())
  const queryFrame = computed(() => activeDevice.value ? rtuFrameHex([Number(activeDevice.value.address), 4, 0, 1, 0, 22]) : '')
  const responseFrame = computed(() => activeDevice.value ? rtuFrameHex([Number(activeDevice.value.address), 4, 44,
    ...activeDevice.value.inputRegisters.slice(0, 22).flatMap(v => [Number(v) >>> 8 & 255, Number(v) & 255])]) : '')
  const gatewayId = computed({ get: () => activeGateway.value.gatewayId, set: (value) => { activeGateway.value.gatewayId = value } })
  const brokerUrl = computed({ get: () => activeGateway.value.brokerUrl, set: (value) => { activeGateway.value.brokerUrl = value } })
  const qos = computed({ get: () => activeGateway.value.qos, set: (value) => { activeGateway.value.qos = value } })
  const interval = computed({ get: () => activeGateway.value.interval, set: (value) => { activeGateway.value.interval = value } })
  const running = ref(false)
  let timer, disposed = false

  const downTopic = computed(() => gatewayMeterCount(selectedGatewayKey.value) ? 'firetopic/__meterTopicKey/down' : activeGateway.value.downTopic || `firetopic/${gatewayId.value || '{gatewayId}'}/down`)
  const upTopic = computed(() => gatewayMeterCount(selectedGatewayKey.value) ? 'firetopic/__meterTopicKey/up' : activeGateway.value.upTopic || `firetopic/${gatewayId.value || '{gatewayId}'}/up`)

  function gatewayMeterCount(gatewayKey) {
    return Object.values(meterGatewayKeys.value).filter((key) => key && key === gatewayKey).length
  }

  // 电表与探测器互斥。只解除设备关联，不删除设备或修改测量数据。
  function bindMeter(type, targetGatewayKey) {
    if (!Object.hasOwn(meterGatewayKeys.value, type) || runtimeSessions.value.length) return false
    if (targetGatewayKey && !gateways.value.some((gateway) => gateway.key === targetGatewayKey)) return false
    if (meterGatewayKeys.value[type] === targetGatewayKey) return true
    const gateway = gateways.value.find((item) => item.key === targetGatewayKey)
    const detached = devices.value.filter((device) => targetGatewayKey && device.gatewayKey === targetGatewayKey)
    detached.forEach((device) => { device.gatewayKey = '' })
    meterGatewayKeys.value[type] = targetGatewayKey
    addLog('电表', `${type === 'dc' ? '直流电表' : '单相交流电表'}${gateway ? `已绑定 ${gateway.name}` : '已解除网关绑定'}${detached.length ? `；${detached.length} 台极早期设备已解除绑定，数据保留在未绑定列表` : ''}。`)
    return true
  }

  function updateVersion(device, field, startAddress, count, value) {
    const sanitized = [...value].slice(0, count).map((char) => char.charCodeAt(0) <= 0x7f ? char : '?').join('')
    device[field] = sanitized
    writeAsciiRegisters(device.inputRegisters, startAddress, count, sanitized)
  }

  function updateFault(bit, enabled) {
    const current = Number(activeDevice.value.inputRegisters[1]) || 0
    activeDevice.value.inputRegisters[1] = enabled ? current | (1 << bit) : current & ~(1 << bit)
  }

  function isFaultSelected(bit) {
    return (Number(activeDevice.value.inputRegisters[1]) & (1 << bit)) !== 0
  }

  function enumOptions(tab, address) {
    if (tab === 'input') {
      if (address === 3) return alarmLabels.map((label, value) => ({ value, label }))
      if (address === 4) return [{ value: 0, label: '正常' }, { value: 1, label: '异常' }]
      if (address >= 10 && address <= 13) return [{ value: 0, label: '正常' }, { value: 1, label: '报警' }]
      if (address === 14) return [{ value: 0, label: '正数' }, { value: 1, label: '负数' }]
      if (address === 18) return [{ value: 1, label: 'CCD-480-DP0' }, { value: 2, label: 'CCD-180-DP0' }, { value: 3, label: 'CCD-280-00P' }, { value: 4, label: 'CCD-480-DPP' }, { value: 5, label: 'CCD-280-000' }, { value: 6, label: 'CCD-280-DP0' }, { value: 7, label: 'CCD-280-DPP' }, { value: 8, label: 'CCD-480-DP' }]
      if (address === 19) return [{ value: 0, label: '吸气式感烟火灾探测器' }]
      if (address === 20) return [19, 20, 21, 22].map((value) => ({ value, label: `${value}（世纪）` }))
      if (address === 22) return Array.from({ length: 12 }, (_, index) => ({ value: index + 1, label: `${index + 1} 月` }))
    }
    if (tab === 'holding') {
      if (address === 1) return [{ value: 20, label: '20（固定值）' }]
      if (address === 3) return Array.from({ length: 12 }, (_, index) => ({ value: index + 1, label: `${index + 1} 月` }))
      if (address === 5) return Array.from({ length: 24 }, (_, value) => ({ value, label: `${value} 时` }))
      if (address === 6 || address === 7 || address === 29 || address === 31) return Array.from({ length: 60 }, (_, value) => ({ value, label: `${value}` }))
      if (address === 28 || address === 30) return Array.from({ length: 24 }, (_, value) => ({ value, label: `${value} 时` }))
      if (address === 33) return [0, 7, 8, 9, 10].map((value) => ({ value, label: ({ 0: '115200', 7: '9600', 8: '14400', 9: '19200', 10: '38400' })[value] }))
      if (address === 34) return [{ value: 1, label: '极灵敏' }, { value: 2, label: '灵敏' }, { value: 3, label: '普通' }]
      if (address === 35 || address === 36) return [{ value: 0, label: '关闭' }, { value: 1, label: '开启' }]
      if (address === 38) return ['无测试', '火警 2 测试', '火警 1 测试', '巡警测试', '预警测试', '示警测试', '故障测试'].map((label, value) => ({ value, label }))
      if (address === 39) return ['洁净环境', '普通环境', '复杂环境', '严苛环境'].map((label, index) => ({ value: index + 1, label }))
    }
    return null
  }

  function numberRange(tab, address) {
    if (tab === 'input') {
      if (address === 1) return [0, 1000]
      if (address >= 5 && address <= 9) return [1, 255]
      if (address >= 15 && address <= 17) return [0, 9999]
      if (address === 21) return [0, 99]
    }
    if (tab === 'holding') {
      if (address === 2) return [0, 99]
      if (address === 4) return [1, 31]
      if (address >= 8 && address <= 27 && address % 2 === 0) return [1, 255]
      if (address >= 8 && address <= 27 && address % 2 === 1) return [0, 60]
      if (address === 32) return [1, 255]
      if (address === 37) return [20, 100]
    }
    return [0, 65535]
  }

  function asciiChar(address) {
    if (!isAsciiAddress(address)) return ''
    const value = Number(activeDevice.value.inputRegisters[address - 1])
    return value >= 0x20 && value <= 0x7e ? String.fromCharCode(value) : value === 0 ? '␀' : '·'
  }

  function isAsciiAddress(address) {
    return (address >= 0x17 && address <= 0x21) || (address >= 0x22 && address <= 0x2f) || (address >= 0x30 && address <= 0x3f)
  }

  function fieldOptions(tab, address, current) {
    const options = enumOptions(tab, address)
    if (!options) return []
    return options.some((option) => option.value === Number(current)) ? options : [{ value: Number(current), label: `未定义值 (${current})` }, ...options]
  }

  function nextAddress() {
    return nextAddressForGateway(selectedGatewayKey.value)
  }

  function nextAddressForGateway(gatewayKey) {
    const used = new Set(devices.value.filter((device) => device.gatewayKey === gatewayKey).map((device) => Number(device.address)))
    for (let address = 1; address <= 247; address += 1) if (!used.has(address)) return address
    return 1
  }

  function addDevice(copy = false) {
    if (gatewayMeterCount(selectedGatewayKey.value)) return
    if (gatewayDevices.value.length >= 247) return
    const device = createDevice(gatewayDevices.value.length + 1, copy ? activeDevice.value : undefined, selectedGatewayKey.value)
    device.address = nextAddress()
    if (gatewayDevices.value.some((item) => item.address === device.address)) return
    devices.value.push(device)
    selectedDeviceId.value = device.id
    addLog('设备', `已${copy ? '复制' : '添加'}设备 ${device.name}，从站地址 ${device.address}。`)
  }

  function removeDevice(device) {
    if (devices.value.length <= 1) return
    devices.value = devices.value.filter((item) => item.id !== device.id)
    if (selectedDeviceId.value === device.id) selectedDeviceId.value = gatewayDevices.value[0]?.id
    addLog('设备', `已从本地列表移除 ${device.name}。`)
  }

  function updateAddress(device, value) {
    const address = Number(value)
    if (!Number.isInteger(address) || address < 1 || address > 247 || (device.gatewayKey && devices.value.some((item) => item.gatewayKey === device.gatewayKey && item.id !== device.id && Number(item.address) === address))) {
      addLog('设备', `地址 ${value} 无效或已被占用；从站地址需唯一且在 1–247 范围内。`)
      return
    }
    device.address = address
  }

  function gatewayDeviceCount(gatewayKey) {
    return devices.value.filter((device) => device.gatewayKey === gatewayKey).length
  }

  function bindDevice(device, targetGatewayKey) {
    if (!device || !gateways.value.some((gateway) => gateway.key === targetGatewayKey)) return
    if (gatewayMeterCount(targetGatewayKey)) {
      addLog('设备', '该网关已绑定电表，请先解除其全部电表绑定，再绑定极早期设备。')
      return
    }
    if (device.gatewayKey === targetGatewayKey) return
    const sourceGateway = gateways.value.find((gateway) => gateway.key === device.gatewayKey)
    const targetGateway = gateways.value.find((gateway) => gateway.key === targetGatewayKey)
    if (gatewayDeviceCount(targetGatewayKey) >= 247) {
      addLog('设备', `${targetGateway?.name || '目标网关'}已达到 247 台设备上限，无法绑定 ${device.name}。`)
      return
    }
    const oldGatewayKey = device.gatewayKey
    const occupied = new Set(devices.value.filter((item) => item.gatewayKey === targetGatewayKey && item.id !== device.id).map((item) => Number(item.address)))
    const oldAddress = device.address
    // 同一网关下地址唯一；迁移时仅在冲突时分配空闲地址，寄存器数据保持原样。
    if (occupied.has(Number(device.address))) device.address = nextAddressForGateway(targetGatewayKey)
    device.gatewayKey = targetGatewayKey
    selectGateway(targetGateway)
    selectedDeviceId.value = device.id
    addLog('设备', `已将 ${device.name} 从 ${sourceGateway?.name || oldGatewayKey} 绑定到 ${targetGateway.name}；上行主题 ${deviceUpTopic.value}${oldAddress !== device.address ? `；地址冲突，已由 ${oldAddress} 调整为 ${device.address}` : ''}。`)
  }

  function uniqueGatewayId(base, copy = false) {
    const used = new Set(gateways.value.map((gateway) => gateway.gatewayId))
    if (!copy && !used.has(base)) return base
    let suffix = 1
    let candidate = copy ? `${base}-copy` : `${base}-${suffix}`
    while (used.has(candidate)) candidate = copy ? `${base}-copy-${++suffix}` : `${base}-${++suffix}`
    return candidate
  }

  function addGateway(copy = false) {
    const source = copy ? activeGateway.value : null
    const number = gateways.value.length + 1
    const gateway = source
      ? { ...source, key: `gateway-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`, name: `${source.name} 副本`, gatewayId: uniqueGatewayId(source.gatewayId, true), clientId: '', downTopic: '', upTopic: '' }
      : { key: `gateway-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`, name: `网关 ${number}`, gatewayId: uniqueGatewayId(`gateway-${String(number).padStart(3, '0')}`), brokerUrl: '', qos: '0', interval: '2000' }
    gateways.value.push(gateway)
    const copyingMeterGateway = copy && gatewayMeterCount(source.key) > 0
    const sourceDevices = copy ? gatewayDevices.value : []
    if (sourceDevices.length) {
      sourceDevices.forEach((device) => {
        const duplicate = createDevice(device.address, device, gateway.key)
        duplicate.address = device.address
        devices.value.push(duplicate)
      })
    } else if (!copyingMeterGateway) {
      devices.value.push(createDevice(1, undefined, gateway.key))
    }
    selectedGatewayKey.value = gateway.key
    selectedDeviceId.value = gatewayDevices.value[0]?.id
    addLog('网关', `已${copy ? '复制' : '添加'}${gateway.name}，包含 ${gatewayDevices.value.length} 台设备。`)
  }

  function removeGateway(gateway) {
    if (gateways.value.length <= 1 || runtimeSessions.value.length) return
    const remaining = gateways.value.filter((item) => item.key !== gateway.key)
    const deviceDestinations = remaining.filter((item) => !gatewayMeterCount(item.key))
    const destination = remaining[0]
    const movingDevices = devices.value.filter((device) => device.gatewayKey === gateway.key)
    const availableCapacity = deviceDestinations.reduce((total, item) => total + Math.max(0, 247 - gatewayDeviceCount(item.key)), 0)
    if (movingDevices.length > availableCapacity) {
      addLog('网关', `无法删除${gateway.name}：其他网关没有足够的从站地址容纳其 ${movingDevices.length} 台设备。`)
      return
    }
    movingDevices.forEach((device) => {
      const target = deviceDestinations.find((item) => gatewayDeviceCount(item.key) < 247)
      const occupied = new Set(devices.value.filter((item) => item.gatewayKey === target.key && item.id !== device.id).map((item) => Number(item.address)))
      device.gatewayKey = target.key
      if (occupied.has(Number(device.address))) device.address = [...Array(247)].map((_, index) => index + 1).find((address) => !occupied.has(address))
    })
    for (const type of Object.keys(meterGatewayKeys.value)) {
      if (meterGatewayKeys.value[type] === gateway.key) meterGatewayKeys.value[type] = ''
    }
    gateways.value = remaining
    if (selectedGatewayKey.value === gateway.key) {
      selectedGatewayKey.value = destination.key
      selectedDeviceId.value = gatewayDevices.value[0]?.id
    }
    const destinations = [...new Set(movingDevices.map((device) => remaining.find((item) => item.key === device.gatewayKey).name))]
    addLog('网关', `已移除${gateway.name}${movingDevices.length ? `，${movingDevices.length} 台设备已转移至${destinations.join('、')}` : '（没有绑定设备）'}。`)
  }

  function selectGateway(gateway) {
    selectedGatewayKey.value = gateway.key
    if (!gatewayMeterCount(gateway.key)) selectedDeviceId.value = gatewayDevices.value[0]?.id
  }

  function setTemplate(key) {
    selectedTemplate.value = key
    const input = activeDevice.value.inputRegisters
    if (key === 'normal') input.splice(0, 17, 120, 0, 0, 0, 200, 180, 150, 120, 100, 0, 0, 0, 0, 0, 2460, 4820, 328)
    if (key === 'alarm') input.splice(0, 17, 880, 0, 4, 0, 200, 180, 150, 120, 100, 1, 0, 0, 0, 0, 5840, 3150, 312)
    if (key === 'fault') input.splice(0, 17, 120, 1, 0, 1, 200, 180, 150, 120, 100, 0, 0, 0, 0, 0, 2480, 4790, 261)
    addLog('预览', `设备 ${activeDevice.value.name} 应用模板：${templates[key]}。`)
  }

  function addLog(kind, message) {
    logs.value.unshift({ time: now(), kind, message })
    logs.value = logs.value.slice(0, 30)
  }

  function previewOnce() {
    if (disposed || !gatewayDevices.value.length) return
    previewCount.value += 1
    lastPreview.value = now()
    addLog('模拟', `${gatewayDevices.value.map((device) => `${device.name}（地址 ${device.address}）`).join('、')} → ${activeGateway.value.name} → ${brokerUrl.value || '未填写 Broker'} → ${upTopic.value}；第 ${previewCount.value} 次本地预览，未发送网络消息。`)
  }

  function stopPreview(message = '已停止本地预览。') {
    clearInterval(timer)
    timer = undefined
    if (running.value) addLog('系统', message)
    running.value = false
  }

  function togglePreview() {
    if (disposed) return
    if (running.value) { stopPreview(); return }
    if (!gatewayDevices.value.length) {
      addLog('系统', '当前网关没有绑定设备，请先添加设备或绑定已有设备。')
      return
    }
    running.value = true
    addLog('系统', '已开始本地预览；不会连接 Broker 或发布网络消息。')
    previewOnce()
    timer = setInterval(previewOnce, Math.max(500, Number(interval.value) || 2000))
  }

  // 计时器只作用于当前选中网关；切换网关或间隔后使用新参数。
  watch(() => [selectedGatewayKey.value, interval.value, gatewayDevices.value.length], () => {
    if (!running.value) return
    if (!gatewayDevices.value.length) { stopPreview('当前网关没有绑定设备，已停止本地预览。'); return }
    clearInterval(timer)
    timer = setInterval(previewOnce, Math.max(500, Number(interval.value) || 2000))
  }, { flush: 'sync' })

  onBeforeUnmount(() => {
    disposed = true
    stopPreview('已离开工具，本地预览已停止；网关与设备配置保留在当前页面会话内。')
  })

  return { templates, selectedTemplate, dataTab, gateways, selectedGatewayKey, activeGateway,
    devices, gatewayDevices, unboundDevices, visibleDevices, selectedDeviceId, activeDevice, deviceGateway, deviceDownTopic, deviceUpTopic, registerRows,
    meterGatewayKeys, meters, gatewayMeterCount, bindMeter,
    faultOptions, deviceAlarm, deviceSeverityClass, faultSummary, temperature, responsePreview, addressHex, queryFrame, responseFrame,
    gatewayId, brokerUrl, qos, interval, running, previewCount, lastPreview, logs, downTopic, upTopic,
    updateVersion, updateFault, isFaultSelected, numberRange, asciiChar, isAsciiAddress, fieldOptions,
    addDevice, removeDevice, updateAddress, gatewayDeviceCount, bindDevice, addGateway, removeGateway,
    selectGateway, setTemplate, togglePreview, previewOnce, stopPreview, runtimeSessions }
}
