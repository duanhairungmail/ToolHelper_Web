import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { useDatabasePlugin } from '../src/composables/useDatabasePlugin.js'

function mount(api) {
  let state
  const messages = []
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() {
    state = useDatabasePlugin({ append: value => messages.push(value), setStatus() {} }, api)
    return () => null
  } })
  app.mount({})
  return { state, messages, unmount: () => app.unmount() }
}
const settle = () => new Promise(resolve => setImmediate(resolve))
const installed = { pluginId: 'dbx', state: 'INSTALLED', platformSupported: true, installRevision: 'revision' }

test('远程插件占用全局任务时禁用 DBX 操作，不显示另一插件的结果', async () => {
  let busy = true
  let mutations = 0
  const vm = mount({
    status: async () => ({ ...installed, busy, currentTask: busy ? { pluginId: 'electerm', taskId: 'remote', status: 'SUCCEEDED', result: { message: '远程插件结果' } } : null }),
    mutate: async () => { mutations++; return { message: 'DBX 已请求启动' } }
  })
  try {
    await settle()
    assert.equal(vm.state.busy.value, true)
    await vm.state.perform('launch')
    assert.equal(mutations, 0)
    assert.equal(vm.messages.includes('远程插件结果'), false)
    busy = false
    await vm.state.refresh()
    await vm.state.perform('launch')
    assert.equal(mutations, 1)
    assert.ok(vm.messages.includes('DBX 已请求启动'))
  } finally { vm.unmount() }
})

test('安装确认显示 DBX 和实包版本，卸载说明完整保留数据', async () => {
  const previous = globalThis.window
  const confirmations = []
  const mutations = []
  globalThis.window = { confirm: value => { confirmations.push(value); return true } }
  const vm = mount({
    status: async () => installed,
    checkUpdate: async () => ({ compatible: true, result: 'AVAILABLE', latestVersion: 'v0.6.72', packageVersion: '0.6.72-beta.20261119.2', size: 1024, releaseSelectionId: 'dbx-selection' }),
    mutate: async (operation, body) => { mutations.push({ operation, body }); return { message: '完成' } }
  })
  try {
    await settle()
    await vm.state.perform('update')
    await vm.state.perform('uninstall')
    assert.match(confirmations[0], /DBX v0.6.72/)
    assert.match(confirmations[0], /0.6.72-beta.20261119.2/)
    assert.match(confirmations[0], /关闭 DBX/)
    assert.equal(confirmations.some(value => value.includes('electerm') || value.includes('${name}')), false)
    assert.match(confirmations[1], /保留.*数据/)
    assert.equal(mutations[0].body.expectedInstallRevision, 'revision')
    assert.equal(mutations[1].body.confirmed, true)
  } finally { vm.unmount(); globalThis.window = previous }
})
