<script setup>
import { computed } from 'vue'
import { useAes } from '@/composables/useAes'

const { draft, capabilities, result, error, pending, randomPending, notice, keyVisible, aadOpen, load, run,
  generateRandom, copy, download, resultToInput, clearContent, clearKey, reset } = useAes()
const isGcm = computed(() => draft.mode === 'GCM')
const isBlockMode = computed(() => ['CBC', 'ECB'].includes(draft.mode))
const isLegacyPackedGcm = computed(() => draft.profile === 'LEGACY_TOOLHELPER' && draft.mode === 'GCM')
const inputLabel = computed(() => draft.operation === 'encrypt' ? '明文输入' : '密文输入')
const availableModes = computed(() => capabilities.value?.modes || ['CBC', 'ECB', 'CTR', 'GCM'])
const availableKeyBits = computed(() => capabilities.value?.modeKeyBits?.[draft.mode] || capabilities.value?.keyBits || [128, 192, 256])
const availableCharsets = computed(() => capabilities.value?.charsets || ['UTF-8'])

function changeOperation(value) {
  draft.operation = value
  if (value === 'encrypt') draft.profile = 'STANDARD'
  if (value === 'decrypt') {
    draft.inputFormat = draft.inputFormat === 'TEXT' ? 'BASE64' : draft.inputFormat
    draft.outputFormat = 'TEXT'
    if (draft.mode !== 'ECB' && !(draft.profile === 'LEGACY_TOOLHELPER' && draft.mode === 'GCM')) draft.ivMode = 'MANUAL'
  } else if (draft.mode !== 'ECB') {
    draft.ivMode = 'AUTO'; draft.iv = ''
  }
}
function changeMode(value) {
  draft.mode = value
  if (!availableKeyBits.value.includes(Number(draft.keyBits))) draft.keyBits = availableKeyBits.value[0] || 128
  draft.padding = ['CBC', 'ECB'].includes(value) ? 'PKCS7' : 'NONE'
  if (value === 'ECB') { draft.ivMode = null; draft.iv = ''; draft.ivFormat = null }
  else if (draft.operation === 'decrypt' && !(draft.profile === 'LEGACY_TOOLHELPER' && value === 'GCM')) draft.ivMode = 'MANUAL'
  else if (draft.operation === 'encrypt') { draft.ivMode = 'AUTO'; draft.iv = ''; draft.ivFormat = 'HEX' }
  else if (draft.profile === 'LEGACY_TOOLHELPER' && value === 'GCM') { draft.ivMode = null; draft.iv = ''; draft.ivFormat = null }
}
function changeManual(event) {
  if (event.target.checked && draft.operation === 'encrypt' && ['GCM', 'CTR'].includes(draft.mode)
      && !globalThis.confirm?.('手动复用GCM nonce或CTR计数范围可能导致密文安全性失效。仅用于互操作测试，确认继续？')) {
    event.target.checked = false; return
  }
  draft.ivMode = event.target.checked ? 'MANUAL' : 'AUTO'
  if (!event.target.checked) draft.iv = ''
}
function downloadAllowed() { download() }
</script>

<template>
  <div class="aes-tool-page" data-od-id="aes-screen">
    <div class="aes-tool-toolbar">
      <div class="aes-operation" role="tablist" aria-label="操作类型">
        <button :class="{ active: draft.operation === 'encrypt' }" role="tab" :aria-selected="draft.operation === 'encrypt'" :disabled="pending" @click="changeOperation('encrypt')">加密</button>
        <button :class="{ active: draft.operation === 'decrypt' }" role="tab" :aria-selected="draft.operation === 'decrypt'" :disabled="pending" @click="changeOperation('decrypt')">解密</button>
      </div>
      <div class="aes-toolbar-right">
        <span class="aes-preview-badge" role="status"><i :class="{ ready: capabilities }"></i>{{ capabilities ? '本机 AES 服务已连接' : '正在连接本机服务…' }}</span>
        <button class="aes-reset" :disabled="pending" @click="reset">重置参数</button>
      </div>
    </div>

    <section class="aes-params" aria-label="AES参数">
      <div class="aes-params-head">
        <div><span class="aes-kicker">AES WORKSPACE</span><h2>加密参数</h2></div>
        <label class="aes-profile"><span>参数方案</span><select v-model="draft.profile" :disabled="draft.operation === 'encrypt' || pending"><option value="STANDARD">标准模式</option><option value="LEGACY_TOOLHELPER" :disabled="!capabilities?.legacyDecrypt">旧版兼容解密</option></select></label>
      </div>
      <div class="aes-param-grid">
        <label class="aes-field aes-mode-field"><span>运算模式</span><select :value="draft.mode" :disabled="pending" @change="changeMode($event.target.value)"><option v-for="item in availableModes" :key="item">{{ item }}</option></select></label>
        <label class="aes-field"><span>密钥长度</span><select v-model.number="draft.keyBits" :disabled="pending"><option v-for="bits in availableKeyBits" :key="bits" :value="bits">AES-{{ bits }}</option></select></label>
        <label v-if="isBlockMode" class="aes-field"><span>填充方式</span><select v-model="draft.padding" :disabled="pending"><option value="PKCS7">PKCS7</option><option value="NONE">无填充</option></select></label>
        <label class="aes-field"><span>文本编码</span><select v-model="draft.charset" :disabled="pending"><option v-for="charset in availableCharsets" :key="charset">{{ charset }}</option></select></label>
        <label class="aes-field"><span>输入格式</span><select v-model="draft.inputFormat" :disabled="pending"><option v-if="draft.operation === 'encrypt'">TEXT</option><option>HEX</option><option>BASE64</option></select></label>
        <label class="aes-field"><span>{{ draft.operation === 'encrypt' ? '密文输出' : '明文输出' }}</span><select v-model="draft.outputFormat" :disabled="pending"><option v-if="draft.operation === 'decrypt'">TEXT</option><option>HEX</option><option>BASE64</option></select></label>
      </div>
      <div class="aes-key-row">
        <label class="aes-field aes-key-input"><span>密钥 KEY <b>· {{ draft.keyBits }} 位 · {{ draft.key ? '按解码字节数校验' : '尚未输入' }}</b></span><div class="aes-input-wrap"><input v-model="draft.key" :type="keyVisible ? 'text' : 'password'" autocomplete="off" spellcheck="false" :disabled="pending" placeholder="输入密钥，或生成随机密钥"><button type="button" :aria-label="keyVisible ? '隐藏密钥' : '显示密钥'" :disabled="pending" @click="keyVisible = !keyVisible">{{ keyVisible ? '隐藏' : '显示' }}</button></div></label>
        <label class="aes-field aes-key-format"><span>KEY 格式</span><select v-model="draft.keyFormat" :disabled="pending"><option>HEX</option><option>TEXT</option><option>BASE64</option></select></label>
        <button class="aes-generate" :disabled="pending || randomPending || !capabilities" @click="generateRandom('KEY')">✦ <span>{{ randomPending ? '生成中…' : '随机生成' }}</span></button>
      </div>
      <div class="aes-clear-key-row"><button class="aes-clear" :disabled="!draft.key || pending" @click="clearKey">清除 KEY</button><span>只在本机处理；KEY不会写入日志或保存为历史记录。</span></div>
      <div v-if="draft.mode !== 'ECB' && !isLegacyPackedGcm" class="aes-nonce-row">
        <div class="aes-nonce-label"><span class="aes-label">{{ isGcm ? '随机数 NONCE' : '初始化向量 IV' }}</span><span class="aes-sub-label">{{ draft.operation === 'decrypt' ? '解密必须使用加密时的原值' : draft.ivMode === 'AUTO' ? '每次加密时由本机安全随机生成' : '手动指定；仅用于互操作' }}</span></div>
        <label class="aes-manual-toggle"><input type="checkbox" :checked="draft.operation === 'decrypt' || draft.ivMode === 'MANUAL'" :disabled="draft.operation === 'decrypt' || pending" @change="changeManual"><span>手动输入</span></label>
        <div class="aes-nonce-value"><code>{{ result?.ivHex || (draft.operation === 'encrypt' && draft.ivMode === 'AUTO' ? '加密时生成新值，不复用上次nonce' : draft.iv || '请填写HEX / TEXT / BASE64') }}</code><span v-if="draft.operation === 'encrypt' && draft.ivMode === 'AUTO'" class="aes-auto-tag">AUTO</span><template v-if="draft.operation === 'decrypt' || draft.ivMode === 'MANUAL'"><input v-model="draft.iv" aria-label="初始化向量或nonce" :disabled="pending" placeholder="IV / nonce"><select v-model="draft.ivFormat" aria-label="IV编码格式" :disabled="pending"><option>HEX</option><option>TEXT</option><option>BASE64</option></select></template><button v-else class="aes-icon-button" title="生成手动IV / nonce" :disabled="pending || randomPending || !capabilities" @click="generateRandom(isGcm ? 'NONCE' : 'IV')">⟳</button></div>
      </div>
      <div v-else-if="draft.mode === 'ECB'" class="aes-nonce-row"><div class="aes-nonce-label"><span class="aes-label">初始化向量 IV</span><span class="aes-sub-label">ECB模式不使用IV，仅为旧协议互操作保留</span></div><span class="aes-auto-tag">N/A</span></div>
      <div v-else class="aes-nonce-row"><div class="aes-nonce-label"><span class="aes-label">随机数 NONCE</span><span class="aes-sub-label">从旧版密文包前12字节读取</span></div><span class="aes-auto-tag">LEGACY PACKAGE</span></div>
      <div v-if="isGcm && draft.profile === 'STANDARD'" class="aes-aad">
        <button class="aes-aad-toggle" :aria-expanded="aadOpen" @click="aadOpen = !aadOpen"><span><b>{{ aadOpen ? '−' : '+' }}</b> 附加认证数据 AAD <small>可选</small></span><span class="aes-aad-hint">GCM · Tag 128 bit <i>{{ aadOpen ? '收起' : '展开' }}</i></span></button>
        <div v-if="aadOpen" class="aes-aad-body"><textarea v-model="draft.aad" :disabled="pending" placeholder="输入参与认证但不会加密的数据"></textarea><div><select v-model="draft.aadFormat" :disabled="pending"><option>TEXT</option><option>HEX</option><option>BASE64</option></select><span>解密时必须提供与加密时相同的AAD。</span></div></div>
      </div>
      <p v-if="draft.profile === 'LEGACY_TOOLHELPER'" class="aes-legacy-note">兼容模式只用于旧ToolHelper数据解密：KEY/IV按旧规则补零或截断，旧GCM使用 nonce || tag || ciphertext；不兼容CFB/OFB/CTS及其它填充。</p>
    </section>

    <div class="aes-workbench">
      <section class="aes-pane aes-input-pane">
        <header class="aes-pane-head"><div><span class="aes-pane-index">01</span><div><h2>{{ inputLabel }}</h2><p>{{ draft.operation === 'encrypt' ? '按输入格式转换为字节后加密' : '输入原始密文；HEX / BASE64需与格式一致' }}</p></div></div><label class="aes-format-select"><span>FORMAT</span><select v-model="draft.inputFormat" :disabled="pending"><option v-if="draft.operation === 'encrypt'">TEXT</option><option>HEX</option><option>BASE64</option></select></label></header>
        <textarea v-model="draft.input" class="aes-editor" :disabled="pending" :placeholder="draft.operation === 'encrypt' ? '输入需要加密的内容…\n\n支持中文、换行与空白。输入按所选编码转为字节。' : '粘贴密文…\n\n核对密钥、模式、填充、IV / nonce 与编码。'" spellcheck="false"></textarea>
        <footer class="aes-pane-footer"><span>{{ draft.input.length.toLocaleString() }} 字符 <i>·</i> 当前页面内存</span><button class="aes-clear" :disabled="!draft.input && !result" @click="clearContent">清空内容</button></footer>
      </section>
      <section class="aes-pane aes-output-pane">
        <header class="aes-pane-head"><div><span class="aes-pane-index output-index">02</span><div><h2>{{ draft.operation === 'encrypt' ? '加密结果' : '解密结果' }}</h2><p>{{ result ? `${result.outputFormat} · ${result.outputBytes.toLocaleString()} 字节` : draft.mode === 'GCM' ? 'C || TAG · nonce单独保存' : '等待本机AES服务返回结果' }}</p></div></div><div class="aes-output-actions"><label class="aes-format-select"><span>FORMAT</span><select v-model="draft.outputFormat" :disabled="pending"><option v-if="draft.operation === 'decrypt'">TEXT</option><option>BASE64</option><option>HEX</option></select></label><button class="aes-icon-button" aria-label="复制结果" title="复制结果" :disabled="!result" @click="copy">⧉</button><button class="aes-icon-button" aria-label="下载结果" title="下载结果" :disabled="!result" @click="downloadAllowed">↓</button></div></header>
        <div class="aes-result-area"><textarea v-if="result" :value="result.output" readonly aria-label="AES操作结果"></textarea><div v-else class="aes-empty-mark"><span>{{ pending ? '…' : '✳' }}</span><strong>{{ pending ? (draft.operation === 'encrypt' ? '正在加密…' : '正在解密…') : '结果将在这里显示' }}</strong><small>{{ pending ? '通过本机Java服务处理' : '参数校验和运算完成后显示' }}</small></div></div>
        <footer class="aes-result-meta"><div><span class="aes-meta-dot" :class="{ verified: result?.integrity === 'AUTHENTICATED' }"></span><span>{{ result ? (result.integrity === 'AUTHENTICATED' ? 'GCM认证通过' : '解密结果未认证') : `${draft.mode}${isBlockMode ? ' · ' + draft.padding : ' · NoPadding'}` }}</span></div><span>{{ result ? `${result.outputBytes.toLocaleString()} bytes` : '尚未运行' }}</span></footer>
        <div v-if="result" class="aes-output-details"><div v-if="result.ivHex"><span>本次IV / nonce</span><code>{{ result.ivHex }}</code></div><div v-if="result.tagHex"><span>认证标签 TAG</span><code>{{ result.tagHex }}</code></div><p v-if="draft.mode === 'GCM'">标准GCM结果布局：密文字节 || 16字节TAG，nonce需单独保存。</p><p v-else-if="draft.operation === 'decrypt'">此模式未认证；填充通过不代表密钥或明文正确。</p><button class="aes-text-button" @click="resultToInput">结果转入{{ draft.operation === 'encrypt' ? '解密' : '加密' }}输入 →</button></div>
      </section>
    </div>

    <div class="aes-action-row"><p v-if="error" class="aes-error" role="alert">{{ error.message }} <small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p><p v-else-if="notice" role="status">{{ notice }}</p><span v-else-if="!capabilities">连接本机AES服务以查看可用算法和字符编码。</span><span v-else>加密请求不会自动重放；解密认证失败时不会显示部分明文。</span><button class="aes-run-button" :disabled="pending || !capabilities" @click="run"><span>{{ pending ? '处理中…' : draft.operation === 'encrypt' ? '加密' : '解密' }}</span><b>↗</b></button></div>
  </div>
</template>
<style scoped>
.aes-tool-page{--ink:#192538;--muted:#8190a4;--line:#e1e7ef;--blue:#3168e0;--mint:#54c5ad;display:flex;min-height:100%;flex-direction:column;gap:14px;color:var(--ink)}
.aes-preview-badge i.ready{background:#47b79e}.aes-clear-key-row{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:4px;color:#a0aaba;font-size:9px}.aes-legacy-note{margin:8px 0 0;padding:8px 10px;border-radius:5px;background:#fff7e9;color:#92703a;font-size:10px}.aes-aad-body>div{display:flex;align-items:center;gap:9px}.aes-aad-body>div select{height:28px;padding:0 18px 0 7px;border:1px solid var(--line);border-radius:5px;background:#fff;font-size:10px}.aes-result-area textarea{width:100%;height:100%;min-height:0;resize:none;padding:14px;border:0;outline:0;background:#172338;color:#eef3fb;font:11px/1.75 ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere}.aes-result-area textarea:focus{outline:2px solid #5273ae;outline-offset:-3px}.aes-result-meta .aes-meta-dot.verified{background:#54c5ad}.aes-output-details{max-height:115px;overflow:auto;padding:8px 12px;background:#1c2a40;color:#9daabd;font-size:9px}.aes-output-details>div{display:grid;grid-template-columns:92px minmax(0,1fr);gap:8px;padding:3px 0}.aes-output-details code{overflow-wrap:anywhere;color:#d5e0f1;font:9px/1.5 ui-monospace,monospace}.aes-output-details p{margin:5px 0;color:#b6c2d4}.aes-text-button{padding:3px 0;border:0;background:none;color:#9ebaf2;font-size:9px}.aes-text-button:hover{color:#fff}.aes-error{color:#b83c47!important}.aes-error small{display:block;color:#9c7580;font-size:9px}
.aes-tool-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}
.aes-operation{display:inline-flex;gap:3px;padding:4px;border:1px solid var(--line);border-radius:9px;background:#f0f3f8}
.aes-operation button{min-width:84px;min-height:36px;border:0;border-radius:6px;background:transparent;color:#77859a;font-weight:650}
.aes-operation button.active{background:#fff;color:var(--ink);box-shadow:0 1px 4px #18263c18}
.aes-toolbar-right{display:flex;align-items:center;gap:14px}.aes-preview-badge{display:flex;align-items:center;gap:7px;color:#77859a;font-size:11px}.aes-preview-badge i,.aes-meta-dot{width:7px;height:7px;border-radius:50%;background:#e5a841}.aes-reset,.aes-clear{border:0;background:none;color:#718097;font-size:11px;font-weight:600}.aes-reset:hover,.aes-clear:hover{color:var(--blue)}
.aes-params{padding:17px 19px 14px;border:1px solid var(--line);border-radius:10px;background:#fff;box-shadow:0 2px 8px #18263c05}
.aes-params-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:13px}.aes-kicker{color:#9aa7b8;font-size:9px;font-weight:750;letter-spacing:.13em}.aes-params-head h2{margin:2px 0 0;font-size:15px;letter-spacing:-.02em}.aes-profile{display:flex;align-items:center;gap:9px;color:var(--muted);font-size:10px}.aes-profile select,.aes-field select{height:35px;min-width:110px;padding:0 27px 0 10px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--ink);font-size:11px}
.aes-param-grid{display:grid;grid-template-columns:repeat(6,minmax(100px,1fr));gap:9px}.aes-field{display:flex;min-width:0;flex-direction:column;gap:5px}.aes-field>span,.aes-label{color:#8290a3;font-size:10px;font-weight:650}.aes-field b{color:#a2adbb;font-weight:500}.aes-field select{width:100%}.aes-mode-field select{border-color:#c7d5f6;background:#f8faff;color:#2d5fc2;font-weight:650}
.aes-key-row{display:grid;grid-template-columns:minmax(200px,1fr) 130px auto;align-items:end;gap:10px;margin-top:12px}.aes-input-wrap{display:flex;height:37px;border:1px solid var(--line);border-radius:6px;background:#fff}.aes-input-wrap:focus-within{border-color:#9bb5f0;box-shadow:0 0 0 3px #3168e012}.aes-input-wrap input{width:100%;min-width:0;padding:0 10px;border:0;outline:0;background:transparent;font:11px ui-monospace,monospace}.aes-input-wrap button{padding:0 10px;border:0;background:transparent;color:#8b98aa;font-size:10px}.aes-key-format select{height:37px}.aes-generate{height:37px;padding:0 12px;border:1px solid #cbd8f4;border-radius:6px;background:#f7f9ff;color:#3866c3;font-size:11px;font-weight:650}.aes-generate:first-letter{color:#889de2}
.aes-nonce-row{display:grid;grid-template-columns:160px auto minmax(230px,1fr);align-items:center;gap:12px;margin-top:12px;padding:10px 12px;border:1px solid #e8edf4;border-radius:7px;background:#fafbfd}.aes-nonce-label{display:grid;gap:2px}.aes-sub-label{color:#a0aabb;font-size:9px}.aes-manual-toggle{display:flex;align-items:center;gap:6px;color:#748399;font-size:10px}.aes-manual-toggle input{accent-color:var(--blue)}.aes-nonce-value{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:10px}.aes-nonce-value code{overflow:hidden;color:#738198;font:10px ui-monospace,monospace;text-overflow:ellipsis;white-space:nowrap}.aes-nonce-value input{width:44%;height:30px;padding:0 8px;border:1px solid var(--line);border-radius:5px;font:10px ui-monospace,monospace}.aes-nonce-value.disabled{opacity:.5}.aes-auto-tag{padding:3px 6px;border-radius:4px;background:#e9f6f2;color:#3a9e88;font-size:8px;font-weight:750;letter-spacing:.08em}
.aes-aad{margin-top:9px}.aes-aad-toggle{display:flex;width:100%;align-items:center;justify-content:space-between;padding:5px 2px;border:0;background:transparent;color:#64748b;text-align:left;font-size:10px}.aes-aad-toggle b{display:inline-grid;width:16px;height:16px;margin-right:5px;place-items:center;border:1px solid #dce3ec;border-radius:4px;color:#73839a}.aes-aad-toggle small{margin-left:5px;color:#a0aaba}.aes-aad-hint{color:#94a0b1}.aes-aad-hint i{margin-left:8px;color:#486fc0;font-style:normal}.aes-aad-body{display:grid;gap:5px}.aes-aad-body textarea{min-height:48px;padding:8px;border:1px solid var(--line);border-radius:5px;resize:vertical;font:11px ui-monospace,monospace}.aes-aad-body>span{color:#96a1b0;font-size:9px}
.aes-workbench{display:grid;min-height:300px;flex:1;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px}.aes-pane{display:flex;min-width:0;min-height:0;flex-direction:column;overflow:hidden;border:1px solid var(--line);border-radius:10px;background:#fff;box-shadow:0 2px 8px #18263c05}.aes-pane-head{display:flex;min-height:61px;align-items:center;justify-content:space-between;gap:10px;padding:10px 13px;border-bottom:1px solid #edf0f5}.aes-pane-head>div:first-child{display:flex;min-width:0;align-items:center;gap:10px}.aes-pane-index{display:grid;width:28px;height:28px;flex:none;place-items:center;border-radius:7px;background:#edf2fc;color:#5376c2;font:700 10px ui-monospace,monospace}.output-index{background:#eaf6f3;color:#32977f}.aes-pane-head h2{margin:0;font-size:12px}.aes-pane-head p{margin:2px 0 0;color:#97a2b1;font-size:9px}.aes-format-select{display:flex;align-items:center;gap:6px}.aes-format-select span{color:#a2adba;font-size:8px;font-weight:750;letter-spacing:.08em}.aes-format-select select{height:28px;padding:0 20px 0 7px;border:1px solid #e6ebf1;border-radius:5px;background:white;color:#718097;font-size:9px}.aes-output-actions{display:flex;align-items:center;gap:5px}.aes-icon-button{width:28px;height:28px;border:1px solid #e7ebf0;border-radius:5px;background:#fff;color:#77869b;font-size:14px}.aes-icon-button:hover{border-color:#b7c7e8;color:#315fba}.aes-editor{width:100%;min-height:0;flex:1;padding:15px;border:0;outline:0;resize:none;background:#fff;color:#344258;font:12px/1.8 ui-monospace,SFMono-Regular,Consolas,monospace}.aes-editor::placeholder{color:#b0b9c6}.aes-pane-footer{display:flex;min-height:35px;align-items:center;justify-content:space-between;padding:0 12px;border-top:1px solid #f0f2f6;color:#a0aaba;font-size:9px}.aes-pane-footer i{padding:0 4px;color:#ccd3dd;font-style:normal}.aes-clear:disabled{opacity:.4}.aes-result-area{display:grid;min-height:0;flex:1;place-items:center;background:#172338;color:#eef3fb}.aes-empty-mark{display:grid;justify-items:center;gap:6px}.aes-empty-mark>span{display:grid;width:35px;height:35px;place-items:center;border:1px solid #35445b;border-radius:10px;color:#7e9edb;font-size:20px}.aes-empty-mark strong{font-size:11px;font-weight:600}.aes-empty-mark small{color:#8997ac;font-size:9px}.aes-result-meta{display:flex;min-height:35px;align-items:center;justify-content:space-between;padding:0 12px;background:#1c2a40;color:#93a0b4;font-size:9px}.aes-result-meta>div{display:flex;align-items:center;gap:7px}.aes-result-meta .aes-meta-dot{width:6px;height:6px;background:#8b98aa}.aes-action-row{display:flex;min-height:46px;align-items:center;justify-content:space-between;gap:12px}.aes-action-row>span,.aes-action-row>p{margin:0;color:#8794a7;font-size:10px}.aes-action-row>p{color:#4771c0}.aes-run-button{display:flex;min-width:118px;height:39px;align-items:center;justify-content:space-between;gap:20px;padding:0 13px;border:0;border-radius:6px;background:#3168df;color:#fff;font-size:11px;font-weight:700;box-shadow:0 4px 12px #3168df2e}.aes-run-button:hover{background:#275acb}.aes-run-button b{font-size:13px}
@media(max-width:1100px){.aes-param-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.aes-workbench{min-height:460px}}
@media(max-width:760px){.aes-tool-page{gap:10px}.aes-params{padding:14px}.aes-workbench{grid-template-columns:1fr;min-height:auto}.aes-pane{min-height:300px}.aes-key-row{grid-template-columns:minmax(0,1fr) 110px}.aes-generate{grid-column:1/-1;justify-self:start}.aes-nonce-row{grid-template-columns:1fr auto}.aes-nonce-value{grid-column:1/-1}.aes-toolbar-right{gap:8px}.aes-preview-badge{font-size:9px}}
@media(max-width:480px){.aes-param-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.aes-profile{align-items:flex-end;flex-direction:column;gap:3px}.aes-operation button{min-width:68px}.aes-preview-badge{max-width:125px;line-height:1.2}.aes-action-row{align-items:flex-start}.aes-action-row>span,.aes-action-row>p{max-width:55%}.aes-format-select span{display:none}}
</style>
