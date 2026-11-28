import test from 'node:test'
import assert from 'node:assert/strict'
import * as Vue from 'vue'
import { createMqttModbusApi } from '../src/api/mqttModbus.js'
import { createEarlyModbusMqttDraft, useEarlyModbusMqtt } from '../src/composables/useEarlyModbusMqtt.js'
import { useMqttModbusSession } from '../src/composables/useMqttModbusSession.js'
import { rtuFrameHex } from '../src/utils/earlyModbusFrame.js'

test('报文预览使用固定 RTU CRC 向量', () => {
  assert.equal(rtuFrameHex([1, 1, 0, 1, 0, 10]), '01 01 00 01 00 0A ED CD')
  assert.equal(rtuFrameHex([1, 4, 2, 3, 9]), '01 04 02 03 09 79 C6')
})

test('MQTT API 只为 GET 刷新令牌重试，不重放结果不明的写入', async () => {
  const calls = []
  let failed = false
  const api = createMqttModbusApi(async (url, options = {}) => {
    calls.push({ url, ...options })
    if (url === '/api/v1/session') return { data: { token: 'local-token' } }
    if (!failed) { failed = true; throw Object.assign(new Error('expired'), { status: 401 }) }
    if (options.method === 'POST') throw Object.assign(new Error('unknown'), { code: 'REQUEST_OUTCOME_UNKNOWN' })
    return { data: [] }
  })
  assert.deepEqual(await api.list(), [])
  assert.equal(calls.filter(c => c.url.endsWith('/sessions')).length, 2)
  await assert.rejects(api.open({ gateway: {} }), { code: 'REQUEST_OUTCOME_UNKNOWN' })
  assert.equal(calls.filter(c => c.method === 'POST').length, 1)
  assert.equal(calls.at(-1).headers['X-ToolHelper-Session'], 'local-token')
})

async function mount(api, draft = createEarlyModbusMqttDraft()) {
  let editor, runtime
  const renderer = Vue.createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() {
    editor = useEarlyModbusMqtt(draft); runtime = useMqttModbusSession(editor, api)
    return () => null
  } })
  app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { editor, runtime, unmount: () => app.unmount() }
}

test('连接传递绑定快照、清空密码、按版本应用数据，并分别停止网关', async () => {
  const server = [], calls = []
  const api = {
    list: async () => structuredClone(server), events: async () => ({ events: [], cursor: 0 }), heartbeat: async () => ({}),
    open: async body => {
      calls.push(structuredClone(body))
      const s = { sessionId: body.gateway.key, gateway: body.gateway, devices: body.devices, state: 'RUNNING', version: 1 }
      server.push(s); return structuredClone(s)
    },
    profile: async (id, body) => {
      const s = server.find(s => s.sessionId === id)
      assert.equal(body.expectedVersion, s.version)
      s.devices = body.devices; s.version++
      return structuredClone(s)
    }, close: async id => { server.splice(server.findIndex(s => s.sessionId === id), 1) }
  }
  const vm = await mount(api)
  try {
    const { editor: e, runtime: r } = vm
    assert.equal(r.ready.value, true)
    e.activeGateway.value.brokerUrl = 'tcp://127.0.0.1:21883'
    r.username.value = 'user'; r.password.value = 'secret'
    await r.connect()
    assert.equal(calls[0].password, 'secret')
    assert.equal(r.password.value, '')
    assert.equal(r.topologyLocked.value, true)
    assert.equal(calls[0].devices.length, 1)
    assert.equal(calls[0].devices[0].gatewayKey, calls[0].gateway.key)
    e.activeDevice.value.inputRegisters[0] = 777
    assert.notEqual(server[0].devices[0].inputRegisters[0], 777)
    await r.applyProfile()
    assert.equal(server[0].devices[0].inputRegisters[0], 777)
    assert.equal(r.active.value.version, 2)
    e.addGateway(); await r.connect()
    assert.equal(r.sessions.value.length, 2)
    await r.stop()
    assert.equal(r.sessions.value.length, 1)
    assert.equal(server[0].gateway.key, e.gateways.value[0].key)
    assert.equal(r.active.value, undefined)
  } finally { vm.unmount() }
})

test('刷新页面恢复运行快照，保留未应用编辑，离开清除心跳计时器', async t => {
  const callbacks = new Map(); let seq = 0
  t.mock.method(globalThis, 'setInterval', callback => { callbacks.set(++seq, callback); return seq })
  t.mock.method(globalThis, 'clearInterval', id => callbacks.delete(id))
  const draft = createEarlyModbusMqttDraft()
  const gateway = { ...draft.gateways.value[0], brokerUrl: 'tcp://127.0.0.1:21883', qos: 0 }
  const device = structuredClone(Vue.toRaw(draft.devices.value[0])); device.id = 'running-device'; device.inputRegisters[0] = 888
  const session = { sessionId: 'running-session', gateway, devices: [device], version: 4, state: 'RUNNING' }
  const api = { list: async () => structuredClone([session]), events: async () => ({ events: [], cursor: 0 }), heartbeat: async () => ({}) }
  const vm = await mount(api, draft)
  assert.equal(vm.editor.devices.value.length, 1)
  assert.equal(vm.editor.activeDevice.value.id, device.id)
  assert.equal(vm.editor.activeDevice.value.inputRegisters[0], 888)
  vm.editor.activeDevice.value.inputRegisters[0] = 777
  await vm.runtime.refresh()
  assert.equal(vm.editor.activeDevice.value.inputRegisters[0], 777)
  assert.equal(callbacks.size, 1)
  vm.unmount()
  assert.equal(callbacks.size, 0)
  const returned = await mount(api, draft)
  try { assert.equal(returned.editor.activeDevice.value.inputRegisters[0], 777) }
  finally { returned.unmount() }
})

test('结果不明的创建仅查询状态协调，不重放请求且显示错误', async () => {
  let opens = 0
  const api = { list: async () => [], open: async () => { opens++; throw new Error('请求结果无法确认') } }
  const vm = await mount(api)
  try {
    vm.runtime.password.value = 'secret'
    await vm.runtime.connect()
    assert.equal(opens, 1)
    assert.equal(vm.runtime.password.value, '')
    assert.match(vm.runtime.error.value, /结果无法确认/)
  } finally { vm.unmount() }
})
