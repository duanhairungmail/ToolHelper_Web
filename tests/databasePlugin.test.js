import test from 'node:test'
import assert from 'node:assert/strict'
import { createDatabasePluginApi } from '../src/api/databasePlugin.js'
import { useDatabaseConnection } from '../src/composables/useDatabaseConnection.js'

test('数据库管理和连接使用 DBX API，密码只在 POST body 中且失败不自动重放', async () => {
  const calls = []
  const api = createDatabasePluginApi(async (url, options) => {
    if (url.endsWith('/session')) return { data: { token: 'token' } }
    calls.push({ url, options })
    if (url.endsWith('/connect')) throw { status: 401, code: 'SESSION_REQUIRED' }
    return { data: { pluginId: 'dbx' } }
  })
  await api.status()
  await api.checkUpdate()
  await api.mutate('launch')
  await assert.rejects(api.mutate('connect', { password: 'once&密码' }), { code: 'SESSION_REQUIRED' })
  assert.deepEqual(calls.map(x => x.url), ['/api/v1/plugins/dbx', '/api/v1/plugins/dbx/update-check', '/api/v1/plugins/dbx/launch', '/api/v1/plugins/dbx/connect'])
  assert.equal(JSON.parse(calls[3].options.body).password, 'once&密码')
})

test('默认端口按数据库切换，密码默认不提交且字段完整传递', async () => {
  let payload
  const form = useDatabaseConnection(async (_, body) => { payload = { ...body } })
  assert.equal(form.port.value, 3306)
  form.type.value = 'POSTGRESQL'
  assert.equal(form.port.value, 5432)
  form.host.value = 'localhost'; form.username.value = 'u'; form.database.value = 'db'; form.ssl.value = true
  form.password.value = 'not-selected'
  await form.connect()
  assert.equal(Object.hasOwn(payload, 'password'), false)
  assert.equal(form.password.value, '')
  assert.deepEqual(payload, { type: 'POSTGRESQL', host: 'localhost', port: 5432, username: 'u', database: 'db', connectionName: '', ssl: true })
})

test('显式密码（包含空密码）提交后立即清空，结果不明不自动重发', async () => {
  let calls = 0
  let payload
  let finish
  const form = useDatabaseConnection(async (_, body) => {
    calls++; payload = { ...body }
    await new Promise(resolve => { finish = resolve })
  })
  form.host.value = '::1'; form.username.value = 'u'; form.transferPassword.value = true
  form.password.value = 'p&秘密'
  const running = form.connect()
  assert.equal(form.password.value, '')
  assert.equal(form.transferPassword.value, false)
  assert.equal(payload.password, 'p&秘密')
  finish(); await running
  form.transferPassword.value = true
  const empty = form.connect()
  assert.equal(payload.password, '')
  finish(); await empty
  assert.equal(calls, 2)
})

test('取消传密或切换数据库清空密码，非法端口不发请求', async () => {
  let calls = 0
  const form = useDatabaseConnection(async () => { calls++ })
  form.transferPassword.value = true; form.password.value = 'secret'; form.transferPassword.value = false
  assert.equal(form.password.value, '')
  form.transferPassword.value = true; form.password.value = 'secret'; form.type.value = 'POSTGRESQL'
  assert.equal(form.password.value, '')
  assert.equal(form.transferPassword.value, false)
  form.host.value = 'localhost'; form.username.value = 'u'; form.port.value = 5432.5
  await form.connect()
  assert.equal(calls, 0)
  assert.ok(form.formError.value)
})
