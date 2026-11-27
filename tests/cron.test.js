import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, nextTick } from 'vue'
import { createCronApi } from '../src/api/cron.js'
import { createCronDraft, useCron } from '../src/composables/useCron.js'

const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(api, draft = createCronDraft()) {
  let state
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useCron(api, draft); return () => null } })
  app.mount({})
  return { state, unmount: () => app.unmount() }
}
function analysis(expression = '0 */5 * * * ?', size = 10) {
  return { mode: 'SPRING_6', normalizedExpression: expression, zoneId: 'Asia/Shanghai', effectiveStart: '2026-11-27T00:00+08:00', windowEnd: '2034-11-27T00:00+08:00', outcome: 'COMPLETE', requestedCount: size, returnedCount: size,
    times: Array.from({ length: size }, (_, i) => ({ localDateTime: '2026-11-27T00:' + String(i + 1).padStart(2, '0'), offset: '+08:00', instant: 'instant-' + i, weekday: '星期五' })) }
}

test('Cron API安全GET恢复会话一次，分析和生成POST不重放', async () => {
  let sessions = 0, caps = 0, posts = 0
  const api = createCronApi(async (url, options = {}) => {
    if (url.endsWith('/session')) return { data: { token: 'token-' + ++sessions } }
    assert.ok(options.headers['X-ToolHelper-Session'])
    if (options.method === 'GET' && ++caps > 1) return { data: { modes: [] } }
    if (options.method === 'POST') { posts++; assert.equal(options.headers['Content-Type'], 'application/json') }
    throw Object.assign(new Error('expired'), { status: 401, code: 'SESSION_REQUIRED' })
  })
  await api.capabilities()
  assert.equal(sessions, 2)
  await assert.rejects(api.analyze({ expression: '* * * * * ?' }), /expired/)
  await assert.rejects(api.generate({ fields: {} }), /expired/)
  assert.equal(posts, 2)
})

test('只显式预览才计算，规范化结果有效且分页默认5条，参数编辑立即失效', async () => {
  let computes = 0
  const vm = mount({ capabilities: async () => ({}), analyze: async () => { computes++; return analysis() } })
  try {
    await settle()
    assert.equal(computes, 0)
    vm.state.draft.expression = ' 0   */5 * * * ? '
    await vm.state.run()
    assert.equal(vm.state.draft.expression, '0 */5 * * * ?')
    assert.equal(vm.state.result.value.times.length, 10)
    assert.equal(vm.state.pageRows.value.length, 5)
    vm.state.page.value = 2
    assert.equal(vm.state.pageRows.value[0].instant, 'instant-5')
    vm.state.pageSize.value = 10
    await nextTick()
    assert.equal(vm.state.page.value, 1)
    assert.equal(vm.state.pageRows.value.length, 10)
    vm.state.draft.zoneId = 'UTC'
    assert.equal(vm.state.result.value, null)
    assert.equal(vm.state.page.value, 1)
  } finally { vm.unmount() }
})

test('旧请求迟到不覆盖新输入，离开页面中止等待且保留草稿', async () => {
  let resolve, signal
  const draft = createCronDraft()
  const vm = mount({ capabilities: async () => ({}), analyze: async (_, s) => { signal = s; return new Promise(done => { resolve = done }) } }, draft)
  await settle()
  const pending = vm.state.run()
  draft.expression = '0 0 9 * * ?'
  assert.equal(signal.aborted, true)
  resolve(analysis())
  await pending
  assert.equal(vm.state.result.value, null)
  assert.equal(draft.expression, '0 0 9 * * ?')
  vm.unmount()
  assert.equal(draft.expression, '0 0 9 * * ?')
})

test('高级表达式不因打开生成器丢失，生成器传递结构化规则并使日周互斥', async () => {
  let body
  const vm = mount({ capabilities: async () => ({}), generate: async value => { body = value; return analysis('0 * * ? * MON') } })
  try {
    await settle()
    vm.state.draft.expression = '0 0 9 L * ?'
    vm.state.tab.value = 'generator'
    assert.equal(vm.state.advanced.value, true)
    assert.equal(vm.state.draft.expression, '0 0 9 L * ?')
    vm.state.rules.day.kind = 'FIXED'
    vm.state.rules.week.kind = 'FIXED'
    vm.state.rules.week.values = '1'
    vm.state.constrainDate('week')
    assert.equal(vm.state.rules.day.kind, 'ALL')
    await vm.state.run(true)
    assert.equal(body.fields.week.kind, 'FIXED')
    assert.deepEqual(body.fields.week.values, [1])
    assert.equal(body.expression, undefined)
    assert.equal(vm.state.draft.expression, '0 * * ? * MON')
    vm.state.rules.minute.kind = 'LIST'; vm.state.rules.minute.values = '0,,5'
    await vm.state.run(true)
    assert.equal(vm.state.error.value.code, 'INVALID_CRON_FIELD_VALUE')
  } finally { vm.unmount() }
})

test('复制全部预览包含所有分页，剪贴板失败保留可手动复制文本，清空只影响本模块', async () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  let copied
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText: async value => { copied = value } } } })
  const vm = mount({ capabilities: async () => ({}), analyze: async () => analysis() })
  try {
    await settle(); await vm.state.run(); await vm.state.copy(true)
    assert.ok(copied.includes('instant-9'))
    assert.ok(copied.includes('Asia/Shanghai'))
    globalThis.navigator.clipboard.writeText = async () => { throw new Error('denied') }
    await vm.state.copy()
    assert.equal(vm.state.error.value.code, 'CLIPBOARD_UNAVAILABLE')
    assert.equal(vm.state.manualCopy.value, '0 */5 * * * ?')
    vm.state.clear()
    assert.equal(vm.state.result.value, null)
    assert.equal(vm.state.draft.expression, '')
    assert.equal(vm.state.manualCopy.value, '')
  } finally {
    vm.unmount()
    if (previous) Object.defineProperty(globalThis, 'navigator', previous)
    else delete globalThis.navigator
  }
})
