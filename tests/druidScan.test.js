import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { createDruidScanApi } from '../src/api/druidScan.js'
import { createDruidDraft, useDruidScan } from '../src/composables/useDruidScan.js'

const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)) }
const job = (taskId = 't1', status = 'COMPLETED') => ({ taskId, status, kind: 'ANONYMOUS', total: 1, done: 1 })
function mount(overrides = {}, confirm = () => true, draft = createDruidDraft()) {
  let state
  const api = { capabilities: async () => ({ limits: { maxTargets: 100 } }), active: async () => null, history: async () => [],
    task: async id => job(id), results: async () => ({ total: 0, items: [] }), ...overrides }
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useDruidScan(api, draft, confirm); return () => null } })
  app.mount({})
  return { state, api, unmount: () => app.unmount() }
}
test('Druid API只重试安全GET，检测/凭据提交不重放，导出带本机会话', async () => {
  let tokens = 0, gets = 0, writes = 0, downloadOptions
  const api = createDruidScanApi(async (url, options) => {
    if (url === '/api/v1/session') return { data: { token: `t${++tokens}` } }
    if (options.method === 'GET') { if (++gets === 1) throw { status: 401 }; return { data: [] } }
    writes++; throw { status: 401 }
  }, async (url, options) => { downloadOptions = options; return { ok: true, blob: async () => new Blob(['zip']) } })
  await api.history(); assert.equal(gets, 2); assert.equal(tokens, 2)
  await assert.rejects(api.credentials({ password: 'test' })); assert.equal(writes, 1)
  await api.download({ format: 'docx' }); assert.equal(downloadOptions.headers['X-ToolHelper-Session'], 't3')
  assert.equal(downloadOptions.method, 'POST')
})
test('恢复当前或最近内存任务，不自动重发检测', async () => {
  let submits = 0
  const retest = { status: 'BLOCKED', conclusion: '本次未复现：全部检查端点的匿名访问已拦截' }
  const vm = mount({ history: async () => [job()], submit: async () => { submits++ }, results: async () => ({ total: 1, items: [{ targetId: 'r1', retest }] }),
    detail: async () => ({ targetId: 'r1', retest, evidence: [{ retest }] }) })
  try {
    await settle(); assert.equal(vm.state.task.value.taskId, 't1'); assert.equal(vm.state.rows.value.length, 1); assert.equal(submits, 0)
    assert.deepEqual(vm.state.rows.value[0].retest, retest)
    await vm.state.showDetail(vm.state.rows.value[0]); assert.deepEqual(vm.state.detail.value.retest, retest)
    assert.deepEqual(vm.state.detail.value.evidence[0].retest, retest); assert.equal(submits, 0)
  }
  finally { vm.unmount() }
})
test('批量按显式行提交，去除空白行，不自动展开地址范围', async () => {
  let body
  const vm = mount({ submit: async value => { body = value; return job('new', 'RUNNING') } })
  try {
    await settle(); vm.state.mode.value = 'batch'; vm.state.batch.value = ' http://a.test/druid/ \n\nhttp://b.test/app/monitor/'
    await vm.state.start(); assert.deepEqual(body.targets, ['http://a.test/druid/', 'http://b.test/app/monitor/'])
    assert.ok(body.requestKey); assert.equal(body.password, undefined)
  } finally { vm.unmount() }
})
test('HTTP凭据单次确认，密码发起即清空；拒绝确认不发送', async () => {
  let accept = false, requests = 0, captured
  let vm
  vm = mount({ credentials: async body => { requests++; captured = { ...body }; assert.equal(vm.state.password.value, ''); return job('c1', 'RUNNING') } }, () => accept)
  try {
    await settle(); vm.state.target.value = 'http://a.test/druid/'; vm.state.username.value = 'u'; vm.state.password.value = 'p'
    await vm.state.start(true); assert.equal(requests, 0); assert.equal(vm.state.password.value, 'p')
    accept = true; await vm.state.start(true); assert.equal(requests, 1); assert.equal(captured.password, 'p'); assert.equal(captured.allowInsecureHttp, true)
  } finally { vm.unmount() }
})
test('任务运行时阻止清空/导出/再次提交，取消只调用取消API', async () => {
  let clears = 0, submits = 0, cancels = 0
  const vm = mount({ active: async () => job('t1', 'RUNNING'), history: async () => [job('t1', 'RUNNING')],
    clear: async () => { clears++ }, submit: async () => { submits++ }, cancel: async () => { cancels++; return job('t1', 'CANCELLING') } })
  try {
    await settle(); assert.equal(vm.state.running.value, true)
    await vm.state.clear(); await vm.state.start(); await vm.state.download(); assert.equal(clears, 0); assert.equal(submits, 0)
    await vm.state.cancel(); assert.equal(cancels, 1)
  } finally { vm.unmount() }
})
test('离开工具不取消任务，凭据清空，迟到响应不覆盖下一页面', async () => {
  let finish, cancels = 0
  const vm = mount({ capabilities: () => new Promise(resolve => { finish = resolve }), cancel: async () => { cancels++ } })
  vm.state.username.value = 'u'; vm.state.password.value = 'p'; vm.unmount()
  finish({ limits: { maxTargets: 100 } }); await settle()
  assert.equal(vm.state.available.value, false); assert.equal(vm.state.password.value, ''); assert.equal(vm.state.username.value, ''); assert.equal(cancels, 0)
})
test('清空只清本模块结果，已过期任务不会重发，选择导出无目标时给出提示', async () => {
  let clears = 0
  const vm = mount({ history: async () => [job()], results: async () => ({ total: 1, items: [{ targetId: 'r1' }] }), clear: async () => { clears++ } })
  try {
    await settle(); vm.state.scope.value = 'selected'; await vm.state.download(); assert.equal(vm.state.error.value.code, 'NO_EXPORT_DATA')
    vm.api.history = async () => []; await vm.state.clear(); assert.equal(clears, 1); assert.equal(vm.state.rows.value.length, 0)
  } finally { vm.unmount() }
})
