import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { createDeviceMacApi } from '../src/api/deviceMac.js'
import { useDeviceMac, createMacDraft } from '../src/composables/useDeviceMac.js'

const settle = () => new Promise(resolve => setImmediate(resolve))
const observation = ip => ({ ipAddress: ip, macAddress: '02:11:22:33:44:55', source: 'WINDOWS_NEIGHBOR', neighborState: 'Reachable', status: 'MAPPING_FOUND', interfaceIndex: 18, checkedAt: '2026-11-25T01:00:00Z' })
const row = (ip = '192.168.1.1') => ({ recordId: crypto.randomUUID(), stationName: '', ipAddress: ip, deviceName: '', deviceModel: '', serialNumber: '', location: '', lastObservation: null })
function mount(overrides = {}, draft = createMacDraft()) {
  let state
  const api = { capabilities: async () => ({ supported: true, maxRows: 1000 }), ledger: async () => ({ revision: 'initial', records: [] }), active: async () => null, ...overrides }
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useDeviceMac(api, draft); return () => null } })
  app.mount({})
  return { state, api, draft, unmount: () => app.unmount() }
}

test('MAC API 刷新会话仅重试GET，POST不重放，二进制导出带会话头', async () => {
  let tokenCount = 0, fail = true, saves = 0, last
  const api = createDeviceMacApi(async (url, options) => {
    if (url === '/api/v1/session') return { data: { token: `t${++tokenCount}` } }
    if (url.endsWith('/ledger/save')) { saves++; throw { status: 401 } }
    if (fail) { fail = false; throw { status: 401 } }
    return { data: { revision: 'initial', records: [] } }
  }, async (url, options) => { last = { url, options }; return { ok: true, blob: async () => new Blob(['zip']) } })
  assert.equal((await api.ledger()).revision, 'initial'); assert.equal(tokenCount, 2)
  await assert.rejects(api.save({ expectedRevision: 'initial', records: [] })); assert.equal(saves, 1)
  const result = await api.download({ format: 'xlsx', columns: ['index'], records: [] })
  assert.equal(tokenCount, 3); assert.equal(last.options.headers['X-ToolHelper-Session'], 't3')
  assert.equal(last.options.method, 'POST'); assert.match(last.url, /\/export$/); assert.match(result.filename, /xlsx$/)
})

test('保存失败保留草稿，成功使用服务端保存快照，编辑后不误判已保存', async () => {
  let fail = true
  const vm = mount({ save: async body => { if (fail) throw { code: 'LEDGER_VERSION_CONFLICT', message: 'conflict' }; return { revision: 'r2', records: body.records } } })
  try {
    await settle(); vm.state.addRow(); vm.state.rows.value[0].ipAddress = '192.168.1.1'; assert.equal(vm.state.dirty.value, true)
    await vm.state.save(); assert.equal(vm.state.error.value.code, 'LEDGER_VERSION_CONFLICT'); assert.equal(vm.state.rows.value.length, 1); assert.equal(vm.state.dirty.value, true)
    fail = false; await vm.state.save(); assert.equal(vm.state.dirty.value, false)
    vm.state.rows.value[0].stationName = 'changed'; assert.equal(vm.state.dirty.value, true)
  } finally { vm.unmount() }
})

test('查询结果绑定记录和原IP，失败保留历史观察且不伪装本轮成功', async () => {
  const original = row(), wrong = row('192.168.1.10')
  let job = null
  const vm = mount({ ledger: async () => ({ revision: 'r1', records: [original, wrong] }), active: async () => job })
  try {
    await settle()
    vm.state.rows.value[1].ipAddress = '192.168.1.11'
    job = { taskId: 't1', status: 'COMPLETED', done: 2, total: 2, results: [
      { recordId: original.recordId, ipAddress: original.ipAddress, status: 'MAPPING_FOUND', observation: observation(original.ipAddress) },
      { recordId: wrong.recordId, ipAddress: wrong.ipAddress, status: 'MAPPING_FOUND', observation: observation(wrong.ipAddress) }
    ] }
    await vm.state.refresh()
    assert.equal(vm.state.rows.value[0].lastObservation.macAddress, '02:11:22:33:44:55')
    assert.equal(vm.state.rows.value[1].lastObservation, null)
    job = { taskId: 't2', status: 'COMPLETED', done: 1, total: 1, results: [{ recordId: original.recordId, ipAddress: original.ipAddress, status: 'NO_NEIGHBOR', message: '未取得映射', observation: null }] }
    await vm.state.refresh(); assert.equal(vm.state.stateText(vm.state.rows.value[0]), '未取得映射')
    assert.ok(vm.state.observation(vm.state.rows.value[0])); assert.equal(vm.state.dirty.value, true)
  } finally { vm.unmount() }
})

test('切换工具保留草稿和任务，不自动取消；重新进入恢复完成结果', async () => {
  const draft = createMacDraft(), original = row()
  draft.rows.value = [original]; draft.loaded.value = true
  draft.task.value = { taskId: 't1', status: 'RUNNING', results: [] }
  let cancels = 0
  const api = { active: async () => null, task: async () => ({ taskId: 't1', status: 'COMPLETED', total: 1, done: 1, results: [{ recordId: original.recordId, ipAddress: original.ipAddress, status: 'MAPPING_FOUND', observation: observation(original.ipAddress) }] }), cancel: async () => { cancels++ } }
  const first = mount(api, draft); first.unmount(); await settle(); assert.equal(cancels, 0)
  const second = mount(api, draft)
  try { await settle(); assert.equal(second.state.rows.value.length, 1); assert.ok(second.state.rows.value[0].lastObservation); assert.equal(second.state.running.value, false) }
  finally { second.unmount() }
})

test('离开页面后迟到的台账响应不覆盖共享草稿', async () => {
  let finish
  const draft = createMacDraft()
  const vm = mount({ ledger: () => new Promise(resolve => { finish = resolve }) }, draft)
  await settle(); vm.unmount(); finish({ revision: 'late', records: [row()] }); await settle()
  assert.equal(draft.loaded.value, false); assert.equal(draft.rows.value.length, 0)
})

test('后端不可用后可重试加载，服务重启不重发旧查询', async () => {
  let offline = true, submits = 0
  const vm = mount({ capabilities: async () => { if (offline) throw { message: 'offline' }; return { supported: true } }, task: async () => { throw { status: 404 } }, submit: async () => { submits++ } })
  try {
    await settle(); assert.equal(vm.state.available.value, false)
    offline = false; vm.state.task.value = { taskId: 'old', status: 'RUNNING' }
    await vm.state.refresh(); assert.equal(vm.state.loaded.value, true); assert.equal(vm.state.available.value, true)
    // 首次加载会清除没有对应草稿的旧任务，后续已有任务的404同样不能自动重发。
    vm.state.task.value = { taskId: 'old', status: 'RUNNING' }; await vm.state.refresh()
    assert.equal(vm.state.task.value, null); assert.equal(submits, 0)
  } finally { vm.unmount() }
})
