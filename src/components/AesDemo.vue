<script setup>
import { computed, ref } from 'vue'

const operation = ref('encrypt')
const profile = ref('standard')
const mode = ref('GCM')
const keyBits = ref('256')
const keyFormat = ref('HEX')
const key = ref('')
const keyVisible = ref(false)
const manualNonce = ref(false)
const nonce = ref('')
const aadOpen = ref(false)
const aad = ref('')
const inputFormat = ref('TEXT')
const outputFormat = ref('BASE64')
const source = ref('')
const notice = ref('')

const isGcm = computed(() => mode.value === 'GCM')
const isBlockMode = computed(() => ['CBC', 'ECB'].includes(mode.value))
const inputLabel = computed(() => operation.value === 'encrypt' ? '明文输入' : '密文输入')

function selectMode(value) {
  mode.value = value
  if (value === 'ECB') manualNonce.value = false
}

function previewAction() {
  notice.value = '这是界面预览，暂不执行加密或解密。'
}

function clearContent() {
  source.value = ''
  notice.value = '输入与结果已清空，参数保留。'
}

function resetDemo() {
  operation.value = 'encrypt'
  profile.value = 'standard'
  mode.value = 'GCM'
  keyBits.value = '256'
  keyFormat.value = 'HEX'
  key.value = ''
  keyVisible.value = false
  manualNonce.value = false
  nonce.value = ''
  aad.value = ''
  aadOpen.value = false
  inputFormat.value = 'TEXT'
  outputFormat.value = 'BASE64'
  source.value = ''
  notice.value = '参数已恢复为默认值。'
}
</script>

<template>
  <div class="aes-demo" data-od-id="aes-demo-screen">
    <div class="aes-demo-toolbar">
      <div class="aes-operation" role="tablist" aria-label="操作类型">
        <button :class="{ active: operation === 'encrypt' }" role="tab" :aria-selected="operation === 'encrypt'" @click="operation = 'encrypt'; profile = 'standard'">加密</button>
        <button :class="{ active: operation === 'decrypt' }" role="tab" :aria-selected="operation === 'decrypt'" @click="operation = 'decrypt'">解密</button>
      </div>
      <div class="aes-toolbar-right">
        <span class="aes-preview-badge"><i></i>界面预览 · 未连接加密服务</span>
        <button class="aes-reset" @click="resetDemo">恢复默认</button>
      </div>
    </div>

    <section class="aes-params" aria-label="加密参数">
      <div class="aes-params-head">
        <div><span class="aes-kicker">CONFIGURATION</span><h2>加密参数</h2></div>
        <label class="aes-profile"><span>参数方案</span><select v-model="profile"><option value="standard">标准模式</option><option value="legacy" :disabled="operation === 'encrypt'">旧版兼容解密</option></select></label>
      </div>

      <div class="aes-param-grid">
        <label class="aes-field aes-mode-field"><span>运算模式</span><select :value="mode" @change="selectMode($event.target.value)"><option>CBC</option><option>ECB</option><option>CTR</option><option>GCM</option></select></label>
        <label class="aes-field"><span>密钥长度</span><select v-model="keyBits"><option value="128">AES-128</option><option value="192">AES-192</option><option value="256">AES-256</option></select></label>
        <label v-if="isBlockMode" class="aes-field"><span>填充方式</span><select><option>PKCS7</option><option>无填充</option></select></label>
        <label class="aes-field"><span>文本编码</span><select><option>UTF-8</option><option>UTF-16LE</option><option>GBK</option><option>ISO-8859-1</option></select></label>
        <label class="aes-field"><span>输入格式</span><select v-model="inputFormat"><option>TEXT</option><option>HEX</option><option>BASE64</option></select></label>
        <label class="aes-field"><span>密文格式</span><select v-model="outputFormat"><option>BASE64</option><option>HEX</option></select></label>
      </div>

      <div class="aes-key-row">
        <label class="aes-field aes-key-input"><span>密钥 KEY <b>· {{ keyBits }} 位</b></span><div class="aes-input-wrap"><input v-model="key" :type="keyVisible ? 'text' : 'password'" autocomplete="off" spellcheck="false" placeholder="输入密钥，或生成随机密钥"><button type="button" :aria-label="keyVisible ? '隐藏密钥' : '显示密钥'" @click="keyVisible = !keyVisible">{{ keyVisible ? '隐藏' : '显示' }}</button></div></label>
        <label class="aes-field aes-key-format"><span>KEY 格式</span><select v-model="keyFormat"><option>HEX</option><option>TEXT</option><option>BASE64</option></select></label>
        <button class="aes-generate" @click="notice = '随机密钥生成按钮仅展示交互位置。'">✦ <span>随机生成</span></button>
      </div>

      <div class="aes-nonce-row">
        <div class="aes-nonce-label"><span class="aes-label">{{ mode === 'ECB' ? '初始化向量 IV' : isGcm ? '随机数 NONCE' : '初始化向量 IV' }}</span><span class="aes-sub-label">{{ mode === 'ECB' ? 'ECB 模式不使用 IV' : manualNonce ? '手动指定，仅用于互操作' : operation === 'encrypt' ? '加密时自动生成' : '解密时使用已有参数' }}</span></div>
        <label v-if="mode !== 'ECB'" class="aes-manual-toggle"><input v-model="manualNonce" type="checkbox"><span>手动输入</span></label>
        <div class="aes-nonce-value" :class="{ disabled: mode === 'ECB' }"><code>{{ mode === 'ECB' ? '不适用' : manualNonce || operation === 'decrypt' ? (nonce || '输入 IV / nonce') : '每次加密自动生成新的安全随机值' }}</code><span v-if="mode !== 'ECB' && !manualNonce && operation === 'encrypt'" class="aes-auto-tag">AUTO</span><input v-if="mode !== 'ECB' && (manualNonce || operation === 'decrypt')" v-model="nonce" aria-label="初始化向量或nonce" placeholder="HEX 编码"></div>
      </div>

      <div v-if="isGcm" class="aes-aad">
        <button class="aes-aad-toggle" :aria-expanded="aadOpen" @click="aadOpen = !aadOpen"><span><b>{{ aadOpen ? '−' : '+' }}</b> 附加认证数据 AAD <small>可选</small></span><span class="aes-aad-hint">GCM · Tag 128 bit <i>{{ aadOpen ? '收起' : '展开' }}</i></span></button>
        <div v-if="aadOpen" class="aes-aad-body"><textarea v-model="aad" placeholder="输入需要参与认证但不加密的数据"></textarea><span>认证成功后才会返回明文；AAD 必须与加密时一致。</span></div>
      </div>
    </section>

    <div class="aes-workbench">
      <section class="aes-pane aes-input-pane">
        <header class="aes-pane-head"><div><span class="aes-pane-index">01</span><div><h2>{{ inputLabel }}</h2><p>{{ operation === 'encrypt' ? '输入将使用上方编码转换为字节' : '粘贴密文，并核对模式、密钥和 IV' }}</p></div></div><label class="aes-format-select"><span>FORMAT</span><select v-model="inputFormat"><option>TEXT</option><option>HEX</option><option>BASE64</option></select></label></header>
        <textarea v-model="source" class="aes-editor" :placeholder="operation === 'encrypt' ? '在此输入需要加密的内容…\n\n支持中文、换行与空白字符。' : '粘贴 HEX 或 Base64 密文…'" spellcheck="false"></textarea>
        <footer class="aes-pane-footer"><span>{{ source.length.toLocaleString() }} 字符 <i>·</i> 输入内容仅保留在当前页面</span><button class="aes-clear" :disabled="!source" @click="clearContent">清空内容</button></footer>
      </section>

      <section class="aes-pane aes-output-pane">
        <header class="aes-pane-head"><div><span class="aes-pane-index output-index">02</span><div><h2>{{ operation === 'encrypt' ? '加密结果' : '解密结果' }}</h2><p>{{ operation === 'encrypt' ? `${outputFormat} · ${isGcm ? 'C || TAG' : '原始密文字节'}` : '选择输出编码以查看明文' }}</p></div></div><div class="aes-output-actions"><label class="aes-format-select"><span>FORMAT</span><select v-model="outputFormat"><option>BASE64</option><option>HEX</option><option v-if="operation === 'decrypt'">TEXT</option></select></label><button class="aes-icon-button" aria-label="复制结果" title="复制结果" @click="notice = '预览模式：没有可复制的加密结果。'">⧉</button><button class="aes-icon-button" aria-label="下载结果" title="下载结果" @click="notice = '预览模式：下载功能尚未接入。'">↓</button></div></header>
        <div class="aes-result-area"><div class="aes-empty-mark"><span>✳</span><strong>结果将在这里显示</strong><small>配置参数并执行{{ operation === 'encrypt' ? '加密' : '解密' }}后查看</small></div></div>
        <footer class="aes-result-meta"><div><span class="aes-meta-dot"></span><span>{{ isGcm ? 'GCM 认证加密' : `${mode} · ${isBlockMode ? 'PKCS7' : 'NoPadding'}` }}</span></div><span>尚未运行</span></footer>
      </section>
    </div>

    <div class="aes-action-row"><p v-if="notice" role="status">{{ notice }}</p><span v-else>标准加密会为 CBC / CTR / GCM 自动生成 IV 或 nonce</span><button class="aes-run-button" @click="previewAction"><span>{{ operation === 'encrypt' ? '加密' : '解密' }}</span><b>↗</b></button></div>
  </div>
</template>

<style scoped>
.aes-demo{--ink:#192538;--muted:#8190a4;--line:#e1e7ef;--blue:#3168e0;--mint:#54c5ad;display:flex;min-height:100%;flex-direction:column;gap:14px;color:var(--ink)}
.aes-demo-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}
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
@media(max-width:760px){.aes-demo{gap:10px}.aes-params{padding:14px}.aes-workbench{grid-template-columns:1fr;min-height:auto}.aes-pane{min-height:300px}.aes-key-row{grid-template-columns:minmax(0,1fr) 110px}.aes-generate{grid-column:1/-1;justify-self:start}.aes-nonce-row{grid-template-columns:1fr auto}.aes-nonce-value{grid-column:1/-1}.aes-toolbar-right{gap:8px}.aes-preview-badge{font-size:9px}}
@media(max-width:480px){.aes-param-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.aes-profile{align-items:flex-end;flex-direction:column;gap:3px}.aes-operation button{min-width:68px}.aes-preview-badge{max-width:125px;line-height:1.2}.aes-action-row{align-items:flex-start}.aes-action-row>span,.aes-action-row>p{max-width:55%}.aes-format-select span{display:none}}
</style>
