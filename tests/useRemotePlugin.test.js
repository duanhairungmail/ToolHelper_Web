import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { useRemotePlugin } from '../src/composables/useRemotePlugin.js'

// 使用 Vue 已有 renderer 驱动生命周期，测试行为而非 CSS 或组件内部结构。
function mount(api) {
  let state
  const messages = []
  const renderer = createRenderer({
    createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {}
  })
  const app = renderer.createApp({ setup() {
    state = useRemotePlugin({ append: (message) => messages.push(message), setStatus() {} }, api)
    return () => null
  } })
  app.mount({})
  return { state, messages, unmount: () => app.unmount() }
}
const settle = () => new Promise((resolve) => setImmediate(resolve))
const installed = { state: 'INSTALLED', platformSupported: true, installRevision: 'revision', currentTask: null }

test('服务故障不会显示为未安装，状态恢复后解除操作禁用', async () => {
  let failed = true
  const vm = mount({ status: async () => {
    if (failed) throw { code: 'SERVICE_UNAVAILABLE', message: '本机服务不可用' }
    return installed
  } })
  try {
    await settle()
    assert.equal(vm.state.badge.value, '本机服务不可用')
    assert.equal(vm.state.usable.value, false)
    failed = false
    await vm.state.refresh()
    assert.equal(vm.state.badge.value, '已安装')
    assert.equal(vm.state.usable.value, true)
    assert.equal(vm.state.error.value, null)
  } finally { vm.unmount() }
})

test('刷新页面接续现有任务，完成结果只报告一次', async () => {
  let running = true
  let mutations = 0
  const task = { taskId: 'task-1', status: 'RUNNING' }
  const vm = mount({
    status: async () => ({ ...installed, currentTask: running ? task : null }),
    task: async () => ({ ...task, status: 'SUCCEEDED', result: { message: '插件已更新' } }),
    mutate: async () => { mutations++ }
  })
  try {
    await settle()
    assert.equal(vm.state.busy.value, true)
    await vm.state.perform('launch')
    assert.equal(mutations, 0)
    running = false
    await vm.state.refresh()
    await vm.state.refresh()
    assert.equal(vm.state.busy.value, false)
    assert.equal(vm.messages.filter((message) => message === '插件已更新').length, 1)
  } finally { vm.unmount() }
})

test('连接结果不确定时核对状态，不重复提交连接', async () => {
  let mutations = 0
  const vm = mount({ status: async () => installed, mutate: async () => {
    mutations++
    throw { code: 'REQUEST_OUTCOME_UNKNOWN', message: '结果需核对' }
  } })
  try {
    await settle()
    await vm.state.perform('connect', { protocol: 'SSH', host: '127.0.0.1', port: 22, username: 'u' })
    await vm.state.refresh()
    assert.equal(mutations, 1)
    assert.equal(vm.state.error.value.code, 'REQUEST_OUTCOME_UNKNOWN')
  } finally { vm.unmount() }
})

test('页面离开后，版本检查不会继续触发确认或安装', async () => {
  let resolveSelection
  let mutations = 0
  const vm = mount({
    status: async () => ({ ...installed, state: 'NOT_INSTALLED' }),
    checkUpdate: () => new Promise((resolve) => { resolveSelection = resolve }),
    mutate: async () => { mutations++ }
  })
  await settle()
  const operation = vm.state.perform('install')
  vm.unmount()
  resolveSelection({ compatible: true })
  await operation
  assert.equal(mutations, 0)
})
