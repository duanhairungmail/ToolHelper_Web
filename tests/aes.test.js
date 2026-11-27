import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, nextTick } from 'vue'
import { createAesApi } from '../src/api/aes.js'
import { createAesDraft, useAes } from '../src/composables/useAes.js'

const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(api, draft = createAesDraft()) {
  let state
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() { state = useAes(api, draft); return () => null } })
  app.mount({})
  return { state, unmount: () => app.unmount() }
}
const result = (output = 'c2VjcmV0', extra = {}) => ({ output, outputFormat: 'BASE64', outputBytes: output.length, inputBytes: 6,
  mode: 'GCM', keyBits: 256, padding: 'NONE', profile: 'STANDARD', ivHex: '00112233445566778899aabb', tagHex: 'aabbccddeeff00112233445566778899', integrity: 'AUTHENTICATED', warnings: [], ...extra })

test('AES API取会话并传JSON；加解密POST遇到401不自动重放', async () => {
  let sessions = 0, posts = 0
  const api = createAesApi(async (url, options = {}) => {
    if (url.endsWith('/session')) return { data: { token: `token-${++sessions}` } }
    if (options.method === 'POST') { posts++; assert.equal(options.headers['Content-Type'], 'application/json'); throw Object.assign(new Error('expired'), { status: 401 }) }
    return { data: { modes: ['GCM'] } }
  })
  await api.capabilities()
  await assert.rejects(api.encrypt({ key: 'secret' }), /expired/)
  assert.equal(sessions, 1)
  assert.equal(posts, 1)
})

test('只显式点击才运行；请求参数正确且编辑输入会立即使结果失效', async () => {
  let submitted
  const draft = createAesDraft()
  draft.key = '00'.repeat(32); draft.input = 'hello'
  const vm = mount({ capabilities: async () => ({ modes: ['GCM'] }), encrypt: async body => { submitted = body; return result() } }, draft)
  try {
    await settle()
    assert.equal(vm.state.result.value, null)
    await vm.state.run()
    assert.equal(submitted.mode, 'GCM')
    assert.equal(submitted.ivMode, 'AUTO')
    assert.equal(submitted.outputFormat, 'BASE64')
    assert.equal(vm.state.result.value.output, 'c2VjcmV0')
    draft.input = 'changed'
    assert.equal(vm.state.result.value, null)
  } finally { vm.unmount() }
})

test('迟到的加密响应不能覆盖新输入，离开页面会取消请求', async () => {
  let finish, requestSignal
  const draft = createAesDraft()
  draft.key = '00'.repeat(32); draft.input = 'first'
  const vm = mount({ capabilities: async () => ({}), encrypt: (_, signal) => { requestSignal = signal; return new Promise(resolve => { finish = resolve }) } }, draft)
  await settle()
  const pending = vm.state.run()
  draft.input = 'second'
  assert.equal(requestSignal.aborted, true)
  finish(result())
  await pending
  assert.equal(vm.state.result.value, null)
  const another = vm.state.run()
  await settle()
  const signal = requestSignal
  vm.unmount()
  assert.equal(signal.aborted, true)
  finish(result())
  await another
})

test('加密结果转解密会带入密文和实际nonce；清除内容保留KEY参数', async () => {
  const draft = createAesDraft()
  draft.key = '11'.repeat(32); draft.input = 'hello'
  const vm = mount({ capabilities: async () => ({}), encrypt: async () => result('密文') }, draft)
  try {
    await settle(); await vm.state.run(); await vm.state.resultToInput(); await nextTick()
    assert.equal(draft.operation, 'decrypt')
    assert.equal(draft.input, '密文')
    assert.equal(draft.inputFormat, 'BASE64')
    assert.equal(draft.iv, '00112233445566778899aabb')
    assert.equal(draft.ivMode, 'MANUAL')
    assert.equal(vm.state.result.value, null)
    vm.state.clearContent()
    assert.equal(draft.input, '')
    assert.equal(draft.key, '11'.repeat(32))
  } finally { vm.unmount() }
})

test('随机KEY始终按HEX写入并改变格式状态，下载和复制只在有结果时可用', async () => {
  let randomRequest, copied
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText: async value => { copied = value } } } })
  const draft = createAesDraft(); draft.keyFormat = 'TEXT'
  const vm = mount({ capabilities: async () => ({}), random: async body => { randomRequest = body; return { value: 'ab'.repeat(32), format: 'HEX', bytes: 32 } } }, draft)
  try {
    await settle(); await vm.state.generateRandom('KEY')
    assert.equal(randomRequest.format, 'HEX')
    assert.equal(draft.keyFormat, 'HEX')
    assert.equal(draft.key, 'ab'.repeat(32))
    vm.state.result.value = result('YWJj')
    await vm.state.copy()
    assert.equal(copied, 'YWJj')
  } finally {
    vm.unmount()
    if (previous) Object.defineProperty(globalThis, 'navigator', previous)
    else delete globalThis.navigator
  }
})
