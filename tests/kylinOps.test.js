import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, nextTick } from 'vue'
import { createKylinOpsApi } from '../src/api/kylinOps.js'
import { useKylinOps, kylinLabel } from '../src/composables/useKylinOps.js'

const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)) }
function mount(overrides = {}) {
  let state
  const api = { capabilities: async () => ({ features: [{ id: 'activation', actions: [] }, { id: 'logs', actions: ['DEPLOY'] }] }),
    current: async () => ({ connectionId: 'c1' }), active: async () => null, backups: async () => [], ...overrides }
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useKylinOps(api, () => true); return () => null } })
  app.mount({})
  return { state, api, unmount: () => app.unmount() }
}

test('补丁修复需确认才安装，扫描和验证只读取最新结果', async () => {
  let reads = 0, writes = 0
  const vm = mount({ capabilities: async () => ({ features: [{ id: 'patch', actions: ['REPAIR'] }] }),
    scan: async body => { reads++; assert.equal(body.feature, 'patch'); return { findings: [{ status: writes ? 'PATCH_VERSION_REACHED' : 'PATCH_REQUIRED' }] } },
    plan: async body => { assert.equal(body.action, 'REPAIR'); return { planId: 'patch-plan' } },
    execute: async body => { writes++; assert.equal(body.planId, 'patch-plan'); return { taskId: 'patch-task', status: 'SUCCEEDED' } }
  })
  try {
    await settle(); vm.state.feature.value = 'patch'; await nextTick()
    await vm.state.inspect(); assert.equal(vm.state.scan.value.findings[0].status, 'PATCH_REQUIRED')
    await vm.state.beginAction('REPAIR'); assert.equal(writes, 0)
    vm.state.closePlan(); await vm.state.approve(); assert.equal(writes, 0)
    await vm.state.beginAction('REPAIR'); await vm.state.approve(); await vm.state.approve()
    assert.equal(writes, 1)
    await vm.state.inspect(); assert.equal(reads, 2)
    assert.equal(vm.state.scan.value.findings[0].status, 'PATCH_VERSION_REACHED')
    assert.equal(kylinLabel('REPAIR'), '修复'); assert.equal(kylinLabel('INSTALLING'), '安装补丁')
  } finally { vm.unmount() }
})

test('sudo预检失败显示具体原因并清空密码，不生成计划或执行任务', async () => {
  let writes = 0
  const failure = { code: 'KYLIN_SUDO_AUTH_FAILED', message: 'sudo密码验证失败，请填写当前SSH登录用户的密码' }
  const vm = mount({ plan: async () => { throw failure }, execute: async () => { writes++ } })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    vm.state.sudoPassword.value = 'incorrect-secret'
    await vm.state.beginAction('DEPLOY'); await vm.state.approve()
    assert.equal(vm.state.error.value.code, failure.code)
    assert.equal(vm.state.error.value.message, failure.message)
    assert.equal(vm.state.sudoPassword.value, '')
    assert.equal(vm.state.plan.value, null); assert.equal(writes, 0)
  } finally { vm.unmount() }
})
test('Kylin API仅GET恢复401，变更POST与取消DELETE均不重放', async () => {
  let tokens = 0, gets = 0, writes = 0
  const api = createKylinOpsApi(async (url, options) => {
    if (url === '/api/v1/session') return { data: { token: `t${++tokens}` } }
    if (options.method === 'GET') { if (++gets === 1) throw { status: 401 }; return { data: null } }
    writes++; throw { status: 401 }
  })
  await api.current(); assert.equal(gets, 2); assert.equal(tokens, 2)
  await assert.rejects(api.execute({ planId: 'p1', confirmed: true })); assert.equal(writes, 1)
  await assert.rejects(api.cancel('t1')); assert.equal(writes, 2)
})
test('参数编辑使已确认预览失效，扫描/计划后清空sudo密码', async () => {
  let submitted
  const vm = mount({ plan: async body => { submitted = structuredClone(body); return { planId: 'p1' } }, scan: async () => ({ findings: [] }) })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick(); vm.state.sudoPassword.value = 'secret'
    await vm.state.preview(); assert.equal(submitted.sudoPassword, 'secret'); assert.equal(vm.state.sudoPassword.value, '')
    vm.state.confirmed.value = true; vm.state.params.value.fileDays = 500; await nextTick()
    assert.equal(vm.state.plan.value, null); assert.equal(vm.state.confirmed.value, false)
    vm.state.sudoPassword.value = 'other'; await vm.state.inspect(); assert.equal(vm.state.sudoPassword.value, '')
  } finally { vm.unmount() }
})
test('执行前清空秘密并消费预览，网络失败只查询任务不重复提交', async () => {
  let calls = 0, active = 0
  const vm = mount({ active: async () => { active++; return null }, execute: async body => {
    calls++; assert.equal(vm.state.plan.value, null); assert.equal(vm.state.sudoPassword.value, ''); assert.equal(vm.state.vncPassword.value, '')
    assert.equal(body.sudoPassword, 'sudo-secret'); throw { code: 'NETWORK_ERROR', message: 'network' }
  } })
  try {
    await settle(); vm.state.plan.value = { planId: 'p1' }; vm.state.confirmed.value = true
    vm.state.sudoPassword.value = 'sudo-secret'; vm.state.vncPassword.value = 'Fixture!'
    await vm.state.execute(); await vm.state.execute()
    assert.equal(calls, 1); assert.ok(active >= 2); assert.equal(vm.state.error.value.code, 'NETWORK_ERROR')
  } finally { vm.unmount() }
})
test('运行任务阻止再次连接扫描和变更，刷新不会自动写入', async () => {
  let writes = 0
  const vm = mount({ active: async () => ({ taskId: 't1', status: 'RUNNING' }),
    connect: async () => { writes++ }, scan: async () => { writes++ }, plan: async () => { writes++ }, execute: async () => { writes++ } })
  try {
    await settle(); await vm.state.connect(); await vm.state.inspect(); await vm.state.preview(); await vm.state.execute()
    assert.equal(writes, 0); assert.equal(vm.state.running.value, true)
  } finally { vm.unmount() }
})
test('离开后迟到的备份响应不更新页面，输入密码清空', async () => {
  let finish
  const vm = mount({ backups: () => new Promise(resolve => { finish = resolve }) })
  await settle(); vm.state.password.value = 'secret'; vm.state.sudoPassword.value = 'sudo'; vm.unmount()
  finish([{ backupId: 'late' }]); await settle()
  assert.equal(vm.state.password.value, ''); assert.equal(vm.state.sudoPassword.value, ''); assert.deepEqual(vm.state.backups.value, [])
})

test('直接部署只生成确认预览，取消不执行；可主动清除连接凭据', async () => {
  let plans = 0, writes = 0
  const vm = mount({ plan: async body => {
    plans++; assert.equal(body.action, 'DEPLOY')
    return { planId: `p${plans}`, expiresAt: new Date(Date.now() + 60000).toISOString() }
  }, execute: async body => {
    writes++; assert.equal(body.sudoPassword, 'one-operation'); assert.equal(body.confirmed, true)
    return { taskId: 't1', status: 'SUCCEEDED' }
  } })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    vm.state.sudoPassword.value = 'discard-me'; await vm.state.beginAction('DEPLOY')
    assert.equal(writes, 0); assert.equal(vm.state.sudoPassword.value, '')
    vm.state.closePlan(); await vm.state.approve(); assert.equal(writes, 0)
    assert.equal(vm.state.sudoRemembered.value, true)
    vm.state.clearSudo()
    await vm.state.beginAction('DEPLOY'); vm.state.confirmed.value = true
    // 主动清除后可重新填写；确认执行仍只提交一次。
    vm.state.sudoPassword.value = 'one-operation'; await vm.state.approve(); await vm.state.approve()
    assert.equal(writes, 1)
  } finally { vm.unmount() }
})

test('当前连接sudo验证一次后跨扫描与部署复用，切换功能和取消计划不清除', async () => {
  const submitted = []
  const vm = mount({
    capabilities: async () => ({ features: [{ id: 'logs', actions: ['DEPLOY'] }, { id: 'vnc', actions: ['DEPLOY'] }] }),
    scan: async body => { submitted.push(body.sudoPassword); return { findings: [] } },
    plan: async body => { submitted.push(body.sudoPassword); return { planId: 'p1' } },
    execute: async body => { submitted.push(body.sudoPassword); return { taskId: 't1', status: 'SUCCEEDED' } }
  })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    vm.state.sudoPassword.value = 'connection-secret'; await vm.state.inspect()
    assert.equal(vm.state.sudoPassword.value, ''); assert.equal(vm.state.sudoRemembered.value, true)
    vm.state.feature.value = 'vnc'; await nextTick(); await vm.state.inspect()
    await vm.state.beginAction('DEPLOY'); vm.state.closePlan()
    await vm.state.beginAction('DEPLOY'); await vm.state.approve()
    assert.deepEqual(submitted, Array(5).fill('connection-secret'))
  } finally { vm.unmount() }
})

test('连接变化、认证失败、主动清除和断开不会继续复用sudo密码', async () => {
  let currentId = 'c1', rejected = false, active = null
  const submitted = []
  const vm = mount({ current: async () => ({ connectionId: currentId }), active: async () => active,
    disconnect: async () => {}, scan: async body => {
      submitted.push(body.sudoPassword)
      if (rejected) throw { code: 'KYLIN_SUDO_AUTH_FAILED', message: 'authentication failed' }
      return { findings: [] }
    } })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    const remember = async () => { vm.state.sudoPassword.value = 'secret'; await vm.state.inspect(); assert.equal(vm.state.sudoRemembered.value, true) }
    await remember(); await vm.state.refresh(); assert.equal(vm.state.sudoRemembered.value, true)
    currentId = 'c2'; await vm.state.refresh(); assert.equal(vm.state.sudoRemembered.value, false)
    await vm.state.inspect(); assert.equal(submitted.at(-1), '')
    await remember(); rejected = true; await vm.state.inspect(); assert.equal(vm.state.sudoRemembered.value, false)
    rejected = false; await remember()
    active = { taskId: 't1', status: 'FAILED', code: 'KYLIN_SUDO_AUTH_FAILED' }
    await vm.state.refresh(); assert.equal(vm.state.sudoRemembered.value, false)
    active = null; await remember(); vm.state.clearSudo(); assert.equal(vm.state.sudoRemembered.value, false)
    await remember(); await vm.state.disconnect(); assert.equal(vm.state.sudoRemembered.value, false)
    assert.equal(vm.state.connection.value, null)
  } finally { vm.unmount() }
})

test('空闲十五分钟清除sudo缓存，后台刷新不续期', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const vm = mount({ scan: async () => ({ findings: [] }) })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    vm.state.sudoPassword.value = 'secret'; await vm.state.inspect()
    context.mock.timers.tick(14 * 60 * 1000); await vm.state.refresh()
    assert.equal(vm.state.sudoRemembered.value, true)
    context.mock.timers.tick(60 * 1000)
    assert.equal(vm.state.sudoRemembered.value, false)
  } finally { vm.unmount() }
})

test('离开页面后迟到的sudo验证结果不能重新缓存密码', async () => {
  let finish
  const vm = mount({ scan: () => new Promise(resolve => { finish = resolve }) })
  await settle(); vm.state.feature.value = 'logs'; await nextTick()
  vm.state.sudoPassword.value = 'secret'; const inspecting = vm.state.inspect()
  vm.unmount(); finish({ findings: [] }); await inspecting
  assert.equal(vm.state.sudoRemembered.value, false); assert.equal(vm.state.sudoPassword.value, '')
})

test('直接卸载不要求备份编号且不误调用部署；预检失败不执行', async () => {
  let writes = 0, submitted
  const vm = mount({ capabilities: async () => ({ features: [{ id: 'logs', actions: ['DEPLOY', 'UNINSTALL'] }] }),
    plan: async body => { submitted = structuredClone(body); throw { code: 'KYLIN_RESTORE_CONFLICT', message: 'conflict' } },
    execute: async () => { writes++ } })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    await vm.state.beginAction('UNINSTALL'); await vm.state.approve()
    assert.equal(submitted.action, 'UNINSTALL'); assert.equal(submitted.params.backupId, '')
    assert.equal(writes, 0); assert.equal(vm.state.plan.value, null)
    assert.equal(vm.state.error.value.code, 'KYLIN_RESTORE_CONFLICT')
  } finally { vm.unmount() }
})

test('预检与确认执行共用本次sudo密码，输入框清空后不必二次输入', async () => {
  let writes = 0
  const vm = mount({ plan: async body => {
    assert.equal(body.sudoPassword, 'single-use')
    return { planId: 'p1', expiresAt: new Date(Date.now() + 60000).toISOString() }
  }, execute: async body => { writes++; assert.equal(body.sudoPassword, 'single-use'); return { taskId: 't1', status: 'SUCCEEDED' } } })
  try {
    await settle(); vm.state.feature.value = 'logs'; await nextTick()
    vm.state.sudoPassword.value = 'single-use'; await vm.state.beginAction('DEPLOY')
    assert.equal(vm.state.sudoPassword.value, '')
    await vm.state.approve(); assert.equal(writes, 1)
  } finally { vm.unmount() }
})
