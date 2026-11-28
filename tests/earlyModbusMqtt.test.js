import test from 'node:test'
import assert from 'node:assert/strict'
import * as Vue from 'vue'
import { createEarlyModbusMqttDraft, useEarlyModbusMqtt } from '../src/composables/useEarlyModbusMqtt.js'

function mount(draft = createEarlyModbusMqttDraft()) {
  let state
  const renderer = Vue.createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useEarlyModbusMqtt(draft); return () => null } })
  app.mount({})
  return { state, unmount: () => app.unmount() }
}

test('电表绑定解除网关下全部设备关联并保留数据，两块表可共用或分别绑定网关', () => {
  const vm = mount()
  try {
    const s = vm.state
    const firstGateway = s.activeGateway.value
    const device = s.activeDevice.value
    device.inputRegisters[0] = 777
    s.addDevice()
    assert.equal(s.bindMeter('dc', firstGateway.key), true)
    assert.equal(s.gatewayDevices.value.length, 0)
    assert.equal(s.unboundDevices.value.length, 2)
    assert.equal(device.inputRegisters[0], 777)
    assert.equal(s.downTopic.value, 'firetopic/__meterTopicKey/down')
    assert.equal(s.bindMeter('ac', firstGateway.key), true)
    assert.equal(s.gatewayMeterCount(firstGateway.key), 2)
    s.addDevice()
    assert.equal(s.devices.value.length, 2)
    s.bindDevice(device, firstGateway.key)
    assert.equal(device.gatewayKey, '')
    s.addGateway()
    const secondGateway = s.activeGateway.value
    assert.equal(s.bindMeter('ac', secondGateway.key), true)
    assert.equal(s.gatewayMeterCount(firstGateway.key), 1)
    assert.equal(s.gatewayMeterCount(secondGateway.key), 1)
    assert.equal(s.unboundDevices.value.length, 3)
    assert.equal(s.bindMeter('dc', 'missing-gateway'), false)
    assert.equal(s.meterGatewayKeys.value.dc, firstGateway.key)
  } finally { vm.unmount() }
})

test('解除最后一块电表后可重新绑定原设备，恢复自定义主题且保留地址冲突处理', () => {
  const vm = mount()
  try {
    const s = vm.state
    const gateway = s.activeGateway.value
    gateway.upTopic = 'custom/up'
    const device = s.activeDevice.value
    s.bindMeter('dc', gateway.key)
    s.bindMeter('ac', gateway.key)
    s.bindMeter('dc', '')
    s.bindDevice(device, gateway.key)
    assert.equal(device.gatewayKey, '')
    s.bindMeter('ac', '')
    s.addDevice()
    s.bindDevice(device, gateway.key)
    assert.equal(device.gatewayKey, gateway.key)
    assert.equal(device.address, 2)
    assert.equal(s.deviceUpTopic.value, 'custom/up')
    assert.equal(s.unboundDevices.value.length, 0)
  } finally { vm.unmount() }
})

test('删除电表网关清理两块表关联；设备迁移排除电表网关，复制不复制电表绑定', () => {
  const vm = mount()
  try {
    const s = vm.state
    const first = s.activeGateway.value
    s.bindMeter('dc', first.key)
    s.bindMeter('ac', first.key)
    s.addGateway(true)
    assert.equal(s.gatewayMeterCount(s.activeGateway.value.key), 0)
    assert.equal(s.gatewayDevices.value.length, 0)
    s.addDevice()
    const second = s.activeGateway.value
    s.removeGateway(second)
    assert.equal(s.gateways.value.length, 2, '仅剩电表网关时不应迁入探测器')
    s.addGateway()
    const third = s.activeGateway.value
    s.removeGateway(second)
    assert.equal(s.gateways.value.length, 2)
    assert.equal(s.gatewayDeviceCount(first.key), 0)
    assert.equal(s.gatewayDeviceCount(third.key), 2)
    s.removeGateway(first)
    assert.deepEqual(s.meterGatewayKeys.value, { dc: '', ac: '' })
    assert.equal(s.unboundDevices.value.length, 1)
  } finally { vm.unmount() }
})

test('存在运行会话时拒绝电表改绑与解除；电表绑定随工具草稿恢复', () => {
  const draft = createEarlyModbusMqttDraft()
  const vm = mount(draft)
  const s = vm.state
  s.bindMeter('dc', s.activeGateway.value.key)
  s.runtimeSessions.value.push({ sessionId: 'running' })
  assert.equal(s.bindMeter('dc', ''), false)
  assert.equal(s.bindMeter('ac', s.activeGateway.value.key), false)
  vm.unmount()
  const returned = mount(draft)
  try {
    assert.equal(returned.state.gatewayMeterCount(s.activeGateway.value.key), 1)
    assert.equal(returned.state.unboundDevices.value.length, 1)
  } finally { returned.unmount() }
})

test('切换电表网关保留设备编辑与预览，展示其他网关设备且不改变实际路由', () => {
  const vm = mount()
  try {
    const s = vm.state
    const meterGateway = s.activeGateway.value
    const detachedDevice = s.activeDevice.value
    detachedDevice.inputRegisters[0] = 777
    s.bindMeter('dc', meterGateway.key)
    s.addGateway()
    const deviceGateway = s.activeGateway.value
    const otherDevice = s.activeDevice.value
    s.selectGateway(meterGateway)
    assert.equal(s.activeDevice.value.id, otherDevice.id)
    assert.equal(s.visibleDevices.value.length, 2)
    assert.equal(s.registerRows.value.length, 63)
    assert.equal(s.deviceGateway.value.key, deviceGateway.key)
    assert.equal(s.deviceDownTopic.value, `firetopic/${deviceGateway.gatewayId}/down`)
    assert.equal(s.deviceUpTopic.value, `firetopic/${deviceGateway.gatewayId}/up`)
    assert.equal(s.upTopic.value, 'firetopic/__meterTopicKey/up')
    s.selectedDeviceId.value = detachedDevice.id
    assert.equal(s.activeDevice.value.inputRegisters[0], 777)
    assert.equal(s.deviceGateway.value, undefined)
    assert.equal(s.deviceDownTopic.value, '')
    assert.equal(s.deviceUpTopic.value, '')
    assert.ok(s.queryFrame.value.startsWith('01 04 '))
    assert.ok(s.responseFrame.value.includes('03 09'))
    s.selectGateway(deviceGateway)
    s.selectedDeviceId.value = detachedDevice.id
    s.selectGateway(meterGateway)
    assert.equal(s.activeDevice.value.id, detachedDevice.id)
    assert.equal(detachedDevice.gatewayKey, '')
    assert.equal(otherDevice.gatewayKey, deviceGateway.key)
  } finally { vm.unmount() }
})

test('电表网关只有未绑定设备时切换仍自动选中设备草稿', () => {
  const vm = mount()
  try {
    const s = vm.state
    const gateway = s.activeGateway.value
    const device = s.activeDevice.value
    s.bindMeter('dc', gateway.key)
    s.selectedDeviceId.value = undefined
    s.selectGateway(gateway)
    assert.equal(s.activeDevice.value.id, device.id)
    assert.equal(s.registerRows.value.length, 63)
    assert.ok(s.responseFrame.value.length > 0)
  } finally { vm.unmount() }
})

test('设备只绑定一个网关，多台设备可绑定同一网关，路由随绑定和网关配置更新', () => {
  const vm = mount()
  try {
    const s = vm.state
    const first = s.activeDevice.value
    const originalGateway = s.gateways.value[0]
    first.inputRegisters[0] = 777
    s.addGateway()
    const target = s.gateways.value[1]
    const existing = s.activeDevice.value
    s.bindDevice(first, target.key)
    assert.equal(first.gatewayKey, target.key)
    assert.equal(first.inputRegisters[0], 777)
    assert.notEqual(first.address, existing.address)
    assert.equal(s.gatewayDevices.value.length, 2)
    assert.equal(s.deviceGateway.value.key, target.key)
    assert.equal(s.deviceDownTopic.value, `firetopic/${target.gatewayId}/down`)
    assert.equal(s.deviceUpTopic.value, `firetopic/${target.gatewayId}/up`)
    target.gatewayId = 'site-gateway'
    assert.equal(s.deviceDownTopic.value, 'firetopic/site-gateway/down')
    assert.equal(s.deviceUpTopic.value, 'firetopic/site-gateway/up')
    s.previewOnce()
    assert.ok(s.logs.value[0].message.includes('firetopic/site-gateway/up'))
    target.downTopic = 'custom/query'
    assert.equal(s.deviceDownTopic.value, 'custom/query')
    s.selectGateway(originalGateway)
    assert.equal(s.gatewayDevices.value.length, 0)
    assert.equal(s.activeDevice.value, undefined)
    assert.deepEqual(s.registerRows.value, [])
    const count = s.previewCount.value
    s.previewOnce()
    assert.equal(s.previewCount.value, count)
    s.addDevice()
    assert.equal(s.activeDevice.value.gatewayKey, originalGateway.key)
    assert.equal(s.activeDevice.value.address, 1)
  } finally { vm.unmount() }
})

test('改绑到满员网关会拒绝，保留原绑定、地址和数据', () => {
  const vm = mount()
  try {
    const s = vm.state
    const device = s.activeDevice.value
    const originalKey = device.gatewayKey
    s.addGateway()
    const target = s.gateways.value[1]
    for (let index = 1; index < 247; index += 1) s.addDevice()
    s.bindDevice(device, target.key)
    assert.equal(device.gatewayKey, originalKey)
    assert.equal(device.address, 1)
    assert.ok(s.logs.value[0].message.includes('247 台设备上限'))
    s.bindDevice(device, 'missing-gateway')
    assert.equal(device.gatewayKey, originalKey)
  } finally { vm.unmount() }
})

test('复制网关创建独立设备，删除空网关或迁移设备后仍保留唯一绑定', () => {
  const vm = mount()
  try {
    const s = vm.state
    const original = s.activeDevice.value
    s.addGateway(true)
    const copy = s.activeDevice.value
    assert.notEqual(copy.id, original.id)
    assert.notEqual(copy.gatewayKey, original.gatewayKey)
    copy.inputRegisters[0] = 999
    assert.notEqual(original.inputRegisters[0], 999)
    const emptyGateway = s.gateways.value[0]
    s.bindDevice(original, copy.gatewayKey)
    s.removeGateway(emptyGateway)
    assert.equal(s.gateways.value.length, 1)
    assert.equal(s.devices.value.length, 2)
    assert.ok(s.devices.value.every((device) => device.gatewayKey === s.gateways.value[0].key))
    assert.equal(new Set(s.devices.value.map((device) => device.address)).size, 2)
  } finally { vm.unmount() }
})

test('切换工具保留配置，离开时释放预览计时器，返回后不自动启动', (t) => {
  const callbacks = new Map()
  let sequence = 0
  t.mock.method(globalThis, 'setInterval', (callback, delay) => {
    const id = ++sequence
    callbacks.set(id, { callback, delay })
    return id
  })
  t.mock.method(globalThis, 'clearInterval', (id) => callbacks.delete(id))
  const draft = createEarlyModbusMqttDraft()
  const vm = mount(draft)
  const s = vm.state
  assert.equal(callbacks.size, 0)
  assert.equal(s.brokerUrl.value, '')
  s.brokerUrl.value = 'mqtt://test-host:1883'
  s.activeDevice.value.inputRegisters[0] = 888
  s.togglePreview()
  assert.equal(callbacks.size, 1)
  assert.equal(s.previewCount.value, 1)
  s.interval.value = '5000'
  assert.equal(callbacks.size, 1)
  assert.equal([...callbacks.values()][0].delay, 5000)
  const lateTick = [...callbacks.values()][0].callback
  vm.unmount()
  assert.equal(callbacks.size, 0)
  assert.equal(s.running.value, false)
  lateTick()
  assert.equal(s.previewCount.value, 1)
  const returned = mount(draft)
  try {
    assert.equal(returned.state.brokerUrl.value, 'mqtt://test-host:1883')
    assert.equal(returned.state.activeDevice.value.inputRegisters[0], 888)
    assert.equal(returned.state.running.value, false)
    assert.equal(callbacks.size, 0)
    assert.equal(createEarlyModbusMqttDraft().gateways.value[0].brokerUrl, '')
  } finally { returned.unmount() }
})

test('切换到空网关停止本地预览，日志有界，寄存器各表分别保留', (t) => {
  const callbacks = new Map()
  let sequence = 0
  t.mock.method(globalThis, 'setInterval', (callback) => { callbacks.set(++sequence, callback); return sequence })
  t.mock.method(globalThis, 'clearInterval', (id) => callbacks.delete(id))
  const vm = mount()
  try {
    const s = vm.state
    const first = s.activeDevice.value
    const firstGateway = s.gateways.value[0]
    s.addGateway()
    s.bindDevice(first, s.gateways.value[1].key)
    s.togglePreview()
    assert.equal(s.running.value, true)
    s.selectGateway(firstGateway)
    assert.equal(s.running.value, false)
    assert.equal(callbacks.size, 0)
    s.togglePreview()
    assert.equal(s.running.value, false)
    s.addDevice()
    assert.equal(s.registerRows.value.length, 63)
    s.dataTab.value = 'holding'
    s.activeDevice.value.holdingRegisters[32] = 9
    assert.equal(s.registerRows.value.length, 39)
    assert.equal(s.registerRows.value[32].value, 9)
    assert.ok(s.fieldOptions('holding', 33, 9).some((option) => option.label === '19200'))
    s.dataTab.value = 'coils'
    assert.equal(s.registerRows.value.length, 10)
    for (let index = 0; index < 40; index++) s.previewOnce()
    assert.equal(s.logs.value.length, 30)
  } finally { vm.unmount() }
})
