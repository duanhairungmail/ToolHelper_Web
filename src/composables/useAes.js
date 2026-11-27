import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { aesApi } from '../api/aes.js'

export function createAesDraft() {
  return reactive({ operation: 'encrypt', profile: 'STANDARD', mode: 'GCM', keyBits: 256, padding: 'NONE',
    charset: 'UTF-8', key: '', keyFormat: 'HEX', ivMode: 'AUTO', iv: '', ivFormat: 'HEX',
    aad: '', aadFormat: 'TEXT', input: '', inputFormat: 'TEXT', outputFormat: 'BASE64' })
}

export function useAes(api = aesApi, draft = createAesDraft()) {
  const capabilities = ref(null), result = ref(null), error = ref(null), pending = ref(false), notice = ref('')
  const keyVisible = ref(false), aadOpen = ref(false), disposed = ref(false), randomPending = ref(false)
  let version = 0, operationController, loadController, randomController

  function invalidate() {
    version++
    operationController?.abort()
    operationController = null
    result.value = null
    pending.value = false
    error.value = null
  }

  watch(draft, invalidate, { deep: true, flush: 'sync' })

  async function load() {
    loadController?.abort()
    const controller = new AbortController(); loadController = controller
    try { capabilities.value = await api.capabilities(controller.signal); if (!disposed.value) error.value = null }
    catch (failure) { if (!disposed.value && failure.name !== 'AbortError') error.value = failure }
  }

  function requestBody() {
    const decrypting = draft.operation === 'decrypt'
    const ivExcluded = draft.mode === 'ECB' || (draft.profile === 'LEGACY_TOOLHELPER' && draft.mode === 'GCM')
    return {
      profile: draft.profile, mode: draft.mode, keyBits: Number(draft.keyBits), padding: draft.padding,
      charset: draft.charset, key: draft.key, keyFormat: draft.keyFormat,
      ivMode: ivExcluded ? null : decrypting ? 'MANUAL' : draft.ivMode,
      iv: ivExcluded || draft.ivMode === 'AUTO' && !decrypting ? null : draft.iv,
      ivFormat: ivExcluded || draft.ivMode === 'AUTO' && !decrypting ? null : draft.ivFormat,
      aad: draft.mode === 'GCM' && draft.profile === 'STANDARD' ? draft.aad : '',
      aadFormat: draft.aadFormat,
      input: draft.input, inputFormat: draft.inputFormat, outputFormat: draft.outputFormat,
      manualRiskConfirmed: draft.ivMode === 'MANUAL'
    }
  }

  async function run() {
    if (pending.value || disposed.value) return
    invalidate()
    const current = version, controller = new AbortController(); operationController = controller
    pending.value = true; notice.value = ''; error.value = null
    try {
      const body = requestBody()
      const data = draft.operation === 'encrypt' ? await api.encrypt(body, controller.signal) : await api.decrypt(body, controller.signal)
      if (disposed.value || current !== version || controller.signal.aborted) return
      result.value = data
      notice.value = draft.operation === 'encrypt' ? '加密完成；请与密文一并保存本次IV/nonce。' : data.integrity === 'AUTHENTICATED' ? 'GCM认证通过，解密完成。' : '解密完成（未认证，结果正确性无法确认）。'
    } catch (failure) { if (!disposed.value && current === version && !controller.signal.aborted) error.value = failure }
    finally { if (operationController === controller) { operationController = null; pending.value = false } }
  }

  async function generateRandom(kind) {
    if (pending.value || randomPending.value || disposed.value) return
    const current = version, controller = new AbortController(); randomController = controller; randomPending.value = true
    error.value = null
    try {
      const data = await api.random({ kind, keyBits: kind === 'KEY' ? Number(draft.keyBits) : null, format: 'HEX' }, controller.signal)
      if (disposed.value || current !== version) return
      if (kind === 'KEY') { draft.key = data.value; draft.keyFormat = 'HEX' }
      else { draft.iv = data.value; draft.ivFormat = data.format; draft.ivMode = 'MANUAL' }
      notice.value = kind === 'KEY' ? '已生成随机KEY；请妥善保管。' : '已生成随机IV/nonce，可用于手动参数模式。'
    } catch (failure) { if (!disposed.value) error.value = failure }
    finally { if (randomController === controller) randomController = null; randomPending.value = false }
  }

  async function copy() {
    if (!result.value) return
    try { await navigator.clipboard.writeText(result.value.output); notice.value = '已复制结果。' }
    catch { error.value = { code: 'CLIPBOARD_UNAVAILABLE', message: '复制失败，请选中结果手动复制。' } }
  }

  function download() {
    if (!result.value) return
    const blob = new Blob([result.value.output], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = `aes-result-${new Date().toISOString().replaceAll(':', '-')}.txt`; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000); notice.value = '已下载结果文本，不含KEY。'
  }

  function resultToInput() {
    if (!result.value) return
    const currentResult = result.value
    if (draft.operation === 'encrypt') {
      draft.operation = 'decrypt'; draft.profile = 'STANDARD'; draft.input = currentResult.output
      draft.inputFormat = currentResult.outputFormat
      if (draft.mode !== 'ECB') { draft.iv = currentResult.ivHex; draft.ivFormat = 'HEX'; draft.ivMode = 'MANUAL' }
      draft.outputFormat = 'TEXT'
    } else {
      draft.operation = 'encrypt'; draft.profile = 'STANDARD'; draft.input = currentResult.output
      draft.inputFormat = draft.outputFormat
      if (draft.mode !== 'ECB') { draft.iv = ''; draft.ivMode = 'AUTO' }
      draft.outputFormat = 'BASE64'
    }
    notice.value = '结果已转入输入区，请核对参数后再执行。'
  }

  function clearContent() { draft.input = ''; result.value = null; error.value = null; notice.value = '输入和结果已清空，参数保留。' }
  function clearKey() { draft.key = ''; keyVisible.value = false; notice.value = 'KEY已从当前页面清除。' }
  function reset() {
    if ((draft.key || draft.input || result.value) && !globalThis.confirm?.('将清除当前输入、结果和KEY，并恢复默认参数。是否继续？')) return
    invalidate(); Object.assign(draft, createAesDraft()); keyVisible.value = false; aadOpen.value = false; notice.value = '参数已恢复为默认值。'
  }

  onMounted(load)
  onUnmounted(() => {
    disposed.value = true; version++; operationController?.abort(); loadController?.abort(); randomController?.abort()
    draft.key = ''; draft.iv = ''; draft.aad = ''; draft.input = ''; result.value = null
  })

  return { draft, capabilities, result, error, pending, randomPending, notice, keyVisible, aadOpen, load, run, generateRandom,
    copy, download, resultToInput, clearContent, clearKey, reset }
}
