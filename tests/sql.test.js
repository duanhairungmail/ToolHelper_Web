import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { createSqlApi } from '../src/api/sql.js'
import { createSqlDraft, useSql, generationPayload, newValue } from '../src/composables/useSql.js'

const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(api, draft = createSqlDraft()) {
  let state
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useSql(api, draft); return () => null } })
  app.mount({})
  return { state, unmount: () => app.unmount() }
}
test('SQL API恢复GET会话，POST携带JSON且不自动重放', async () => {
  let sessions = 0, calls = 0, posts = 0
  const api = createSqlApi(async (url, options) => {
    if (url.endsWith('/session')) return { data: { token: 'token' + ++sessions } }
    assert.ok(options.headers['X-ToolHelper-Session'])
    if (options.method === 'GET' && ++calls === 2) return { data: { dialects: [] } }
    if (options.method === 'POST') { posts++; assert.equal(options.headers['Content-Type'], 'application/json') }
    throw Object.assign(new Error('expired'), { status: 401 })
  })
  await api.capabilities(); assert.equal(sessions, 2)
  await assert.rejects(api.generate({ table: 'users' }), /expired/)
  assert.equal(posts, 1)
})
test('五类草稿隔离，精确数值传字符串，NULL不携带原值，LIKE不拼接SQL', () => {
  const draft = createSqlDraft(); draft.statementType = 'INSERT'
  draft.forms.INSERT.table = 'users'
  draft.forms.INSERT.values = [{ column: 'id', value: { type: 'NUMBER', value: '900719925474099312345' } }, { column: 'name', value: newValue('NULL') }]
  const body = generationPayload(draft)
  assert.equal(body.insert.values[0].value.value, '900719925474099312345')
  assert.equal(body.insert.values[1].value.value, null)
  draft.statementType = 'SELECT'; assert.equal(generationPayload(draft).table, '')
  draft.forms.SELECT.conditions.push({ column: 'name', operator: 'LIKE', value: newValue(), likeMode: 'CONTAINS' })
  assert.equal(generationPayload(draft).select.conditions[0].likeMode, 'CONTAINS')
})
test('输入改变及页面卸载使迟到响应无效，不覆盖当前结果', async () => {
  let resolve
  const vm = mount({ capabilities: async () => ({}), generate: () => new Promise(done => { resolve = done }) })
  try {
    await settle(); vm.state.form.value.table = 'users'
    const pending = vm.state.runGenerate()
    vm.state.form.value.table = 'devices'
    resolve({ sql: 'SELECT * FROM users;' }); await pending
    assert.equal(vm.state.generated.value, null); assert.equal(vm.state.generating.value, false)
    const late = vm.state.runGenerate(); vm.unmount(); resolve({ sql: 'wrong' }); await late
    assert.equal(vm.state.generated.value, null)
  } finally { vm.unmount() }
})
test('UTF-8严格导入失败保留原文，导入期间编辑/卸载不会覆盖，清空使旧结果失效', async () => {
  const vm = mount({ capabilities: async () => ({}), format: async () => ({ sql: 'SELECT 1' }) })
  try {
    await settle(); vm.state.draft.source = 'select 1'; await vm.state.runFormat()
    assert.equal(vm.state.formatted.value.sql, 'SELECT 1')
    await vm.state.importFile({ name: 'bad.sql', size: 1, arrayBuffer: async () => new Uint8Array([0xff]).buffer })
    assert.equal(vm.state.draft.source, 'select 1'); assert.match(vm.state.error.value.message, /UTF-8/)
    let resolve
    const pending = vm.state.importFile({ name: 'new.sql', size: 8, arrayBuffer: () => new Promise(done => { resolve = done }) })
    vm.state.draft.source = 'select 2'; resolve(new TextEncoder().encode('select 3').buffer); await pending
    assert.equal(vm.state.draft.source, 'select 2'); assert.equal(vm.state.formatted.value, null)
    vm.state.clearInput(); assert.equal(vm.state.draft.source, '')
  } finally { vm.unmount() }
})
test('重置仅当前表单，方言切换使结果失效，送入格式化覆盖需要明确同意', async () => {
  const vm = mount({ capabilities: async () => ({}), generate: async () => ({ sql: 'SELECT 1;' }) })
  try {
    await settle(); vm.state.form.value.table = 'users'; vm.state.draft.forms.INSERT.table = 'saved'
    vm.state.draft.source = 'select old'; await vm.state.runGenerate()
    vm.state.sendToFormatter(() => false); assert.equal(vm.state.draft.source, 'select old')
    vm.state.sendToFormatter(() => true); assert.equal(vm.state.draft.source, 'SELECT 1;'); assert.equal(vm.state.draft.tab, 'format')
    vm.state.draft.dialect = 'POSTGRESQL'; assert.equal(vm.state.draft.escapeMode, 'STANDARD'); assert.equal(vm.state.generated.value, null)
    vm.state.resetForm(); assert.equal(vm.state.form.value.table, ''); assert.equal(vm.state.draft.forms.INSERT.table, 'saved')
  } finally { vm.unmount() }
})
