import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { createPingDraft, usePing } from '../src/composables/usePing.js'

const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(api, draft = createPingDraft()) {
  let state
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = usePing(api, draft); return () => null } })
  app.mount({})
  return { state, unmount: () => app.unmount() }
}

test('页面初始化和解析不开始探测，只有显式开始才提交已解析目标', async () => {
  const calls = []
  const api = {
    capabilities: async () => ({ available: true, limits: { maxTargets: 4096 } }),
    active: async () => null,
    parse: async body => { calls.push(['parse', body]); return { total: 2, targets: ['127.0.0.1', '127.0.0.2'] } },
    submit: async body => { calls.push(['submit', body]); return { taskId: 'task-1', status: 'RUNNING', total: 2, summary: { done: 0 }, results: [] } },
    task: async () => null
  }
  const vm = mount(api)
  try {
    await settle()
    assert.equal(calls.length, 0)
    vm.state.text.value = '127.0.0.1/30'
    await vm.state.parseTargets()
    assert.deepEqual(calls.map(call => call[0]), ['parse'])
    await vm.state.start()
    assert.deepEqual(calls.map(call => call[0]), ['parse', 'submit'])
    assert.deepEqual(calls[1][1].targets, ['127.0.0.1', '127.0.0.2'])
    assert.equal(typeof calls[1][1].requestKey, 'string')
  } finally { vm.unmount() }
})

test('重新进入只接回活动任务；离开页面不自动发送停止请求', async () => {
  let stops = 0
  const active = { taskId: 'active-1', status: 'RUNNING', total: 1, summary: { done: 0 }, results: [] }
  const api = {
    capabilities: async () => ({ available: true, limits: { maxTargets: 4096 } }),
    active: async () => active,
    cancel: async () => { stops++; return { ...active, status: 'CANCELLING' } },
    task: async () => active
  }
  const vm = mount(api)
  await settle()
  assert.equal(vm.state.task.value.taskId, 'active-1')
  vm.unmount()
  assert.equal(stops, 0)
})

test('离开页面期间任务已完成时，通过 sessionStorage 中的任务 ID 恢复结果', async () => {
  const previousStorage = globalThis.sessionStorage
  const values = new Map([['toolhelper.ping.taskId', 'finished-1']])
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  } })
  const completed = { taskId: 'finished-1', status: 'SUCCEEDED', total: 1, summary: { done: 1 }, results: [{ ip: '127.0.0.1' }] }
  const api = {
    capabilities: async () => ({ available: true, limits: { maxTargets: 4096 } }),
    active: async () => null,
    task: async id => { assert.equal(id, 'finished-1'); return completed }
  }
  const vm = mount(api)
  try {
    await settle()
    assert.deepEqual(vm.state.task.value, completed)
    assert.equal(values.get('toolhelper.ping.taskId'), 'finished-1')
  } finally {
    vm.unmount()
    if (previousStorage === undefined) delete globalThis.sessionStorage
    else Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: previousStorage })
  }
})
