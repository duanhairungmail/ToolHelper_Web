import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { useApiValidation } from '../src/composables/useApiValidation.js'

const endpoints = [...Array.from({ length: 13 }, (_, i) => ({ id: `get-${i}`, sideEffect: false })), { id: 'reset', sideEffect: true }]
const initial = () => ({ connected: true, connectionId: 'c1', target: 'https://localhost:8088', busy: false, auto: { running: false }, results: [{ endpointId: 'get-1' }], devices: [], topics: [] })
const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(overrides = {}) {
  let state, clears = 0
  const messages = []
  const api = { state: async () => initial(), endpoints: async () => endpoints, ...overrides }
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useApiValidation({ append: value => messages.push(value), clear: () => { clears++ } }, api); return () => null } })
  app.mount({})
  return { state, api, messages, get clears() { return clears }, unmount: () => app.unmount() }
}

test('后端首次不可用后可恢复目录和状态，清空仅清日志', async () => {
  let offline = true
  const vm = mount({ state: async () => { if (offline) throw Error('offline'); return initial() } })
  try {
    await settle(); assert.equal(vm.state.available.value, false)
    offline = false; await vm.state.refresh()
    assert.equal(vm.state.endpoints.value.length, 14); assert.equal(vm.state.canRun.value, true)
    vm.state.clearLogs(); assert.equal(vm.clears, 1); assert.equal(vm.state.snapshot.value.results.length, 1)
  } finally { vm.unmount() }
})

test('目录单独加载失败后继续重试，不能静默显示空选择', async () => {
  let attempts = 0
  const vm = mount({ endpoints: async () => { if (++attempts === 1) throw Error('catalog unavailable'); return endpoints } })
  try {
    await settle(); assert.match(vm.state.error.value.message, /catalog/)
    await vm.state.refresh(); assert.equal(vm.state.endpoints.value.length, 14)
  } finally { vm.unmount() }
})

test('默认13个GET，手动POST需要确认，自动检测拒绝POST', async () => {
  const before = globalThis.window
  let confirmed = false, calls = 0, payload, prompts = 0
  globalThis.window = { confirm: () => { prompts++; return confirmed } }
  const vm = mount({ verify: async body => { calls++; payload = body }, startAuto: async body => { calls++; payload = body } })
  try {
    await settle(); vm.state.openSelection('verify')
    assert.equal(vm.state.selectedIds.value.length, 13); assert.equal(vm.state.selectedIds.value.includes('reset'), false)
    vm.state.selectedIds.value = ['reset']; await vm.state.submitSelection(); assert.equal(calls, 0)
    confirmed = true; await vm.state.submitSelection(); assert.equal(calls, 1); assert.equal(payload.confirmedSideEffects, true)
    vm.state.openSelection('auto'); vm.state.selectedIds.value = ['reset']; await vm.state.submitSelection()
    assert.equal(calls, 1); assert.equal(prompts, 2)
    vm.state.selectedIds.value = ['get-0']; await vm.state.submitSelection(); assert.equal(calls, 2); assert.equal(payload.cron, '0 */5 * * * ?')
  } finally { vm.unmount(); globalThis.window = before }
})

test('登录发起即清密码、失败不重放，退出页面清密码并取消浏览器等待', async () => {
  let finish, calls = 0, captured, signal
  const vm = mount({ state: async () => ({ ...initial(), connected: false }), login: async (body, passedSignal) => { calls++; captured = body; signal = passedSignal; await new Promise(resolve => { finish = resolve }); throw { code: 'FAILED', message: '登录失败' } } })
  try {
    await settle(); vm.state.form.password = 'fixture-password'
    const pending = vm.state.login(); assert.equal(vm.state.form.password, ''); assert.equal(captured.password, 'fixture-password')
    finish(); await pending; assert.equal(calls, 1); assert.equal(Object.hasOwn(captured, 'password'), false)
    vm.state.form.password = 'not-submitted'; vm.unmount(); assert.equal(vm.state.form.password, ''); assert.equal(signal.aborted, true)
  } finally { if (!signal?.aborted) vm.unmount() }
})

test('后端自动检测状态跨页面恢复，离开页面不发送停止请求', async () => {
  let stops = 0
  const vm = mount({ state: async () => ({ ...initial(), auto: { running: true } }), stopAuto: async () => { stops++ } })
  await settle(); assert.equal(vm.state.running.value, true); assert.equal(vm.state.canRun.value, false)
  vm.unmount(); assert.equal(stops, 0)
})
