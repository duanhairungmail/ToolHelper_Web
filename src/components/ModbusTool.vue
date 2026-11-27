<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { modbusApi } from '@/api/modbus'

const protocols = [{ id: 'SHEN_HONG', name: '申弘版' }, { id: 'NAN_RUI_YI_HE', name: '南瑞怡和版' }]
const ports = ref([]), selectedPort = ref(''), protocol = ref('SHEN_HONG'), baudRate = ref(9600)
const session = ref(null), events = ref([]), cursor = ref(0), selectedAddress = ref(''), polling = ref(false)
const error = ref(''), notice = ref(''), busy = ref(false), resetDialog = ref(false), resetAddress = ref('')
const activeTab = ref('overview')
let disposed = false, timer, heartbeatTimer, controller = new AbortController(), inFlight = false
const connected = computed(() => Boolean(session.value?.sessionId))
const devices = computed(() => session.value?.devices || [])
const selectedDevice = computed(() => devices.value.find(item => item.address === Number(selectedAddress.value)) || null)
const isRunning = computed(() => ['RUNNING', 'QUEUED'].includes(session.value?.operationStatus))
const onlineCount = computed(() => devices.value.filter(device => ['成功', '部分成功', '警告'].includes(device.readStatus)).length)
const noResponseCount = computed(() => devices.value.filter(device => device.readStatus === '失败').length)

function showError(value) { error.value = value?.message || String(value); notice.value = '' }
async function refreshPorts() { try { ports.value = await modbusApi.ports(controller.signal); if (!selectedPort.value && ports.value.length) selectedPort.value = ports.value[0].name } catch (e) { showError(e) } }
async function openPort() {
  if (!selectedPort.value || busy.value) return
  busy.value = true; error.value = ''
  try { session.value = await modbusApi.open({ port: selectedPort.value, baudRate: baudRate.value, protocol: protocol.value }); cursor.value = session.value.cursor || 0; notice.value = '串口已打开；不会自动发送请求' }
  catch (e) { showError(e) } finally { busy.value = false }
}
async function closePort() {
  if (!session.value || busy.value) return
  busy.value = true
  try { if (session.value.polling) await modbusApi.stopPolling(session.value.sessionId); await modbusApi.close(session.value.sessionId); session.value = null; events.value = []; cursor.value = 0; polling.value = false; notice.value = '串口已关闭' }
  catch (e) { showError(e) } finally { busy.value = false }
}
async function startOperation(type, address = null, confirmed = false) {
  if (!connected.value || busy.value || isRunning.value || polling.value) return
  error.value = ''; notice.value = ''
  try { const result = await modbusApi.operate(session.value.sessionId, { type, address, confirmed }); notice.value = `${type === 'SCAN' ? '扫描' : type === 'READ' ? '读取' : '复位'}已提交（${result.operationId}）` }
  catch (e) { showError(e) }
}
function readSelected() {
  const address = Number(selectedAddress.value)
  if (!Number.isInteger(address) || address < 1 || address > 255) { error.value = '请输入1至255的设备地址'; return }
  startOperation('READ', address)
}
function chooseDevice(device) { selectedAddress.value = String(device.address); activeTab.value = 'overview' }
function requestReset() {
  const address = Number(resetAddress.value)
  if (!Number.isInteger(address) || address < 1 || address > 255) { error.value = '复位目标地址必须为1至255'; return }
  resetDialog.value = false; startOperation('RESET', address, true)
}
async function togglePolling() {
  if (!connected.value || busy.value || (isRunning.value && !polling.value)) return
  busy.value = true
  try {
    if (polling.value) { session.value = await modbusApi.stopPolling(session.value.sessionId); polling.value = false }
    else { session.value = await modbusApi.startPolling(session.value.sessionId); polling.value = true }
  } catch (e) { showError(e) } finally { busy.value = false }
}
async function clearEvents() { events.value = []; cursor.value = session.value?.cursor || 0 }
function exportTxt() {
  const lines = events.value.map(e => `${new Date(e.at).toLocaleString()}\t${e.direction}\t${e.hex || e.note}\t${e.verification}`)
  const blob = new Blob([lines.join('\r\n')], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = `modbus-${new Date().toISOString().replaceAll(':', '-')}.txt`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function sync() {
  if (disposed || inFlight) return
  inFlight = true
  try {
    if (!connected.value) {
      const [available, current] = await Promise.all([modbusApi.ports(controller.signal), modbusApi.session(controller.signal)])
      ports.value = available; session.value = current
      if (current) cursor.value = current.cursor || 0
      if (!selectedPort.value && available.length) selectedPort.value = available[0].name
    } else {
      const id = session.value.sessionId
      const [state, delta] = await Promise.all([modbusApi.state(id, controller.signal), modbusApi.events(id, cursor.value, controller.signal)])
      if (delta.gap) { events.value = []; notice.value = '原始报文事件发生缺口，已重新同步' }
      events.value.push(...delta.events); if (events.value.length > 2000) events.value.splice(0, events.value.length - 2000)
      cursor.value = delta.cursor; session.value = state; polling.value = state.polling
    }
  } catch (e) { if (!disposed) { if (e.status === 404) { session.value = null; polling.value = false; notice.value = '串口会话已关闭或租约到期，请重新连接' } else showError(e) } }
  finally { inFlight = false }
}
function startTimers() {
  clearInterval(timer); clearInterval(heartbeatTimer)
  timer = setInterval(sync, 300); heartbeatTimer = setInterval(() => { if (connected.value) modbusApi.heartbeat(session.value.sessionId).catch(() => {}) }, 10000)
}
onMounted(async () => { await sync(); startTimers() })
onBeforeUnmount(() => {
  disposed = true; clearInterval(timer); clearInterval(heartbeatTimer); controller.abort()
  if (session.value?.sessionId) modbusApi.close(session.value.sessionId).catch(() => {})
})
</script>

<template>
  <main class="modbus-workspace">
    <div class="top-row">
      <section class="connection-panel">
        <div class="section-heading"><div><strong>连接参数</strong><span>配置本机串口与协议</span></div><div class="heading-status" :class="connected ? 'is-connected' : ''" role="status"><i></i><span>{{ connected ? '串口已连接' : '串口未连接' }}</span><b>{{ connected ? session.port : '—' }}</b></div></div>
        <div class="connection-fields">
          <label><span>协议</span><select v-model="protocol" :disabled="connected"><option v-for="item in protocols" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
          <label><span>串口</span><select v-model="selectedPort" :disabled="connected"><option value="">请选择</option><option v-for="port in ports" :key="port.name" :value="port.name">{{ port.name }}{{ port.description ? ` · ${port.description}` : '' }}</option></select></label>
          <label><span>波特率</span><select v-model.number="baudRate" :disabled="connected"><option v-for="rate in [1200,2400,4800,9600,19200,38400,57600,115200,230400,460800,921600]" :key="rate" :value="rate">{{ rate }}</option></select></label>
          <label><span>设备地址</span><input v-model="selectedAddress" inputmode="numeric" placeholder="1-255" :disabled="!connected || isRunning || polling"></label>
        </div>
        <div class="connection-actions">
          <button v-if="!connected" class="btn btn-secondary" :disabled="busy" @click="refreshPorts">刷新串口</button>
          <button v-if="!connected" class="btn btn-primary" :disabled="busy || !selectedPort" @click="openPort">连接串口</button>
          <button v-else class="btn btn-secondary" :disabled="busy" @click="closePort">断开串口</button>
        </div>
        <p class="connection-hint">默认 9600、8/N/1；连接后不会自动发送请求。</p>
      </section>

      <aside class="device-panel">
        <div class="section-heading"><div><strong>设备列表</strong><span>站端下辖设备 · {{ devices.length }} 台</span></div><span class="device-count">{{ String(devices.length).padStart(2, '0') }} 台</span></div>
        <div class="device-list" role="listbox" aria-label="站端设备列表">
          <button v-for="device in devices" :key="device.address" class="device-row" :class="{ selected: Number(selectedAddress) === device.address }" :aria-selected="Number(selectedAddress) === device.address" @click="chooseDevice(device)">
            <span class="device-state" :class="{ online: ['成功', '部分成功', '警告'].includes(device.readStatus), offline: device.readStatus === '失败' }"></span>
            <span class="device-main"><strong>设备 {{ String(device.address).padStart(2, '0') }}</strong><small>{{ device.serialText || device.serialHex || '未读取' }}</small></span>
            <span class="device-alarm" :class="{ warning: device.alarmLevel > 0 }">{{ device.alarmName || '未读取' }}</span><span class="row-chevron">›</span>
          </button>
          <div v-if="!devices.length" class="device-empty">{{ connected ? '点击“扫描设备”发现设备' : '连接串口后扫描站端设备' }}</div>
        </div>
        <div class="list-foot"><span class="legend-dot"></span>已响应 {{ onlineCount }} <span class="legend-dot off"></span>无响应 {{ noResponseCount }}<button class="scan-button" :disabled="!connected || busy || isRunning || polling" @click="startOperation('SCAN')">扫描设备</button></div>
      </aside>
    </div>

    <section class="detail-panel">
      <div class="detail-titlebar">
        <div class="selected-head"><div><span class="sub-label">当前设备</span><h3>{{ selectedDevice ? `设备 ${String(selectedDevice.address).padStart(2, '0')}` : '未选择设备' }}<span v-if="selectedDevice" class="alarm-pill">{{ selectedDevice.alarmName || '未读取' }}</span></h3></div><div v-if="selectedDevice" class="update-time"><span>最后更新</span><strong>{{ selectedDevice.updatedAt ? new Date(selectedDevice.updatedAt).toLocaleTimeString() : '—' }}</strong></div></div>
        <div class="detail-actions">
          <button class="btn btn-secondary" :disabled="!connected || !selectedDevice || busy || isRunning || polling" @click="readSelected">读取当前设备</button>
          <button class="btn" :class="polling ? 'btn-stop' : 'btn-secondary'" :disabled="!connected || busy || (isRunning && !polling)" @click="togglePolling">{{ polling ? '停止轮询' : '开始轮询' }}</button>
          <button v-if="protocol === 'NAN_RUI_YI_HE'" class="btn btn-secondary" :disabled="!connected || busy || isRunning || polling" @click="resetAddress = selectedAddress; resetDialog = true">复位设备</button>
        </div>
      </div>

      <p class="operation-status">状态：{{ session?.operationMessage || '空闲' }}<template v-if="polling"> · 每 {{ session?.pollIntervalMs }} ms 逐台读取</template></p>
      <p v-if="notice" class="serial-notice" role="status">{{ notice }}</p><p v-if="error" class="validation-error" role="alert">{{ error }}</p>
      <div class="metric-cards">
        <article class="metric-card primary-metric"><span>热释离子实时值</span><strong>{{ selectedDevice?.ionValue ?? '—' }}<small v-if="selectedDevice?.ionValue != null"> ppb</small></strong><div class="metric-foot">{{ selectedDevice ? '设备读取值' : '选择设备后显示' }}</div></article>
        <article class="metric-card"><span>故障状态</span><strong>{{ selectedDevice?.faultHex ? (selectedDevice.faults?.join('、') || '无故障') : '—' }}</strong><div class="metric-foot">{{ selectedDevice?.faultHex ? `故障字 ${selectedDevice.faultHex}` : '尚无故障数据' }}</div></article>
        <article class="metric-card"><span>通讯状态</span><strong class="comm-value">{{ selectedDevice?.commStatusText || '—' }}</strong><div class="metric-foot">站端报告状态</div></article>
      </div>

      <nav class="detail-tabs" aria-label="设备详情"><button :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">运行数据</button><button :class="{ active: activeTab === 'thresholds' }" @click="activeTab = 'thresholds'">报警阈值</button><button :class="{ active: activeTab === 'slots' }" @click="activeTab = 'slots'">地址槽位</button><button :class="{ active: activeTab === 'frames' }" @click="activeTab = 'frames'">原始报文 <span class="tab-count">{{ events.length }}</span></button></nav>
      <div v-if="activeTab === 'overview'" class="data-grid">
        <div><span>序列号 / ID</span><code>{{ selectedDevice?.serialText || selectedDevice?.serialHex || '未读取' }}</code></div><div><span>工作状态</span><strong>{{ selectedDevice?.workStatusText || '未读取' }}</strong></div>
        <div><span>通讯地址</span><strong>{{ selectedDevice?.deviceAddress ?? selectedDevice?.address ?? '未读取' }}</strong></div><div><span>本轮读取</span><strong>{{ selectedDevice?.readStatus || '未读取' }}{{ selectedDevice?.readMessage ? ` · ${selectedDevice.readMessage}` : '' }}</strong></div>
      </div>
      <div v-else-if="activeTab === 'thresholds'" class="threshold-grid"><div v-for="item in selectedDevice?.thresholds || []" :key="item.name"><span>{{ item.name }}</span><strong>{{ item.value }} <small>ppb</small></strong></div><div v-if="!selectedDevice?.thresholds?.length" class="empty-state">选择设备并读取后显示报警阈值</div></div>
      <div v-else-if="activeTab === 'slots'" class="slot-list"><span v-for="slot in session?.positions || []" :key="slot.index" :class="{ duplicate: slot.duplicate }">#{{ slot.index }} · {{ slot.address || '空' }}<template v-if="slot.communicationState !== null"> · 通讯 {{ slot.communicationState === 1 ? '正常' : slot.communicationState === 0 ? '异常' : slot.communicationState }}</template><template v-if="slot.duplicate"> · 重复</template></span><div v-if="!session?.positions?.length" class="empty-state">扫描后显示站端原始位置</div></div>
      <div v-else class="frame-list">
        <div class="frame-toolbar"><span>HEX 报文保留在有界内存中，页面离开后会话关闭。</span><div><button class="text-button" :disabled="!events.length" @click="clearEvents">清空显示</button><button class="text-button" :disabled="!events.length" @click="exportTxt">导出 TXT</button></div></div>
        <div class="frame-scroll"><div v-for="event in events" :key="event.cursor" class="frame-row"><time>{{ new Date(event.at).toLocaleTimeString() }}</time><b :class="event.direction === 'TX' ? 'tx-tag' : 'rx-tag'">{{ event.direction }}</b><code>{{ event.hex || event.note }}</code><span>{{ event.verification }}</span></div><div v-if="!events.length" class="empty-state">等待操作报文</div></div>
      </div>
    </section>

    <footer class="workspace-footer"><span><i :class="{ offline: !connected }"></i>{{ connected ? '本机服务运行中' : '等待连接本机服务' }}</span><span>Modbus RTU</span><span v-if="session">轮询间隔 {{ session.pollIntervalMs }} ms</span></footer>

    <div v-if="resetDialog" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="reset-title"><h2 id="reset-title">确认复位设备</h2><p>将通过 {{ session?.port }} 向南瑞怡和设备 {{ resetAddress }} 发送一次复位命令。这会复位设备报警状态；超时后结果可能未知，系统不会自动重发。</p><label class="field"><span>复位目标地址</span><input v-model="resetAddress" class="input" inputmode="numeric"></label><div class="button-row top-gap"><button class="btn btn-danger" @click="requestReset">确认发送一次</button><button class="btn btn-secondary" @click="resetDialog = false">取消</button></div></section></div>
  </main>
</template>

<style scoped>
.modbus-workspace{--ink:#17263c;--soft:#75849a;--line:#e5eaf1;--pale:#f5f8fc;--blue:#2563eb;--mint:#16a682;color:var(--ink);padding:0 2px 10px;font-size:13px}.page-heading{display:flex;align-items:center;justify-content:space-between;padding:3px 2px 17px}.eyebrow,.sub-label{color:var(--soft);font-size:10px;font-weight:750;letter-spacing:.09em;text-transform:uppercase}.page-heading h2{margin:4px 0;font-size:23px;letter-spacing:-.025em}.page-heading p{margin:0;color:var(--soft);font-size:12px}.heading-status{display:flex;align-items:center;gap:8px;padding:9px 12px;border:1px solid var(--line);border-radius:8px;color:var(--soft);background:#fff}.heading-status i,.workspace-footer i{width:8px;height:8px;border-radius:50%;background:#aab5c3}.heading-status.is-connected i,.workspace-footer i{background:var(--mint);box-shadow:0 0 0 3px #d9f5eb}.heading-status b{margin-left:6px;color:var(--ink);font:12px var(--font-mono)}.heading-status i.offline,.workspace-footer i.offline{background:#aab5c3;box-shadow:none}.top-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px;align-items:stretch}.connection-panel,.device-panel,.detail-panel{min-width:0;border:1px solid var(--line);border-radius:10px;background:#fff;box-shadow:0 2px 8px #1d35570a}.connection-panel,.device-panel{padding:14px 15px}.section-heading{display:flex;align-items:center;justify-content:space-between;padding:1px 1px 12px}.section-heading>div{display:grid;gap:2px}.section-heading strong{font-size:13px}.section-heading>div span{color:var(--soft);font-size:10px}.setting-icon{display:grid;width:26px;height:26px;place-items:center;border:1px solid var(--line);border-radius:6px;color:var(--soft)}.connection-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.connection-fields label{display:grid;gap:5px;min-width:0}.connection-fields label>span{color:var(--soft);font-size:10px;font-weight:700}.connection-fields select,.connection-fields input{height:36px;min-width:0;padding:0 9px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--ink);font-size:12px}.connection-fields :disabled{background:#f7f9fc;color:#8793a3}.connection-actions{display:flex;justify-content:flex-end;gap:7px;margin-top:12px}.modbus-workspace .btn{min-height:35px;padding:0 11px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--ink);font-size:11px;font-weight:700;white-space:nowrap;cursor:pointer}.modbus-workspace .btn-primary{border-color:var(--blue);background:var(--blue);color:#fff}.modbus-workspace .btn-stop{border-color:#f0b4ae;background:#fff7f6;color:#c43d31}.modbus-workspace .btn:disabled,.scan-button:disabled,.text-button:disabled{opacity:.45;cursor:not-allowed}.connection-hint,.operation-status{margin:9px 0 0;color:var(--soft);font-size:10px}.device-count,.tab-count{padding:3px 7px;border-radius:5px;background:#edf2f8;color:#516176;font:700 10px var(--font-mono)}.device-list{height:132px;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.device-row{display:grid;width:100%;min-height:41px;grid-template-columns:9px minmax(0,1fr) auto 10px;align-items:center;gap:9px;padding:5px 8px;border:1px solid transparent;border-radius:7px;background:transparent;text-align:left;cursor:pointer}.device-row:hover{background:#f8faff}.device-row.selected{border-color:#c8d9ff;background:#f1f6ff}.device-state,.legend-dot{width:7px;height:7px;border-radius:50%;background:var(--mint)}.device-state.offline,.legend-dot.off{background:#a8b2c0}.device-main{display:grid;gap:2px;min-width:0}.device-main strong{font-size:11px}.device-main small{overflow:hidden;color:var(--soft);font:10px var(--font-mono);text-overflow:ellipsis;white-space:nowrap}.device-alarm{padding:3px 6px;border-radius:4px;background:#edf8f4;color:#128367;font-size:9px;font-weight:750}.device-alarm.warning,.alarm-pill.warning{background:#fff6e8;color:#ad6900}.row-chevron{color:#99a5b5;font-size:19px}.device-empty{display:grid;height:100%;place-items:center;color:var(--soft);font-size:11px}.list-foot{display:flex;align-items:center;gap:6px;margin-top:8px;padding-top:9px;border-top:1px solid var(--line);color:var(--soft);font-size:10px}.scan-button{margin-left:auto;padding:5px 8px;border:1px solid var(--line);border-radius:5px;background:white;color:#314866;font-size:10px;font-weight:700;cursor:pointer}.detail-panel{margin-top:14px;padding:15px 17px}.detail-titlebar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-bottom:12px;border-bottom:1px solid var(--line)}.selected-head{display:flex;flex:1;align-items:center;justify-content:space-between}.selected-head h3{display:flex;align-items:center;gap:8px;margin:3px 0 0;font-size:17px}.alarm-pill{padding:3px 7px;border-radius:5px;background:#edf8f4;color:#128367;font-size:9px;font-weight:750}.update-time{display:grid;gap:3px;text-align:right}.update-time span{color:var(--soft);font-size:10px}.update-time strong{font:11px var(--font-mono)}.detail-actions{display:flex;gap:7px}.operation-status{margin:10px 0 0}.serial-notice,.validation-error{margin:8px 0 0;font-size:11px}.serial-notice{color:#4b6585}.validation-error{color:#c43d31}.metric-cards{display:grid;grid-template-columns:1.3fr 1fr 1fr;gap:10px;margin-top:13px}.metric-card{display:grid;min-height:82px;align-content:center;gap:5px;padding:10px 12px;border:1px solid var(--line);border-radius:8px}.metric-card>span{color:var(--soft);font-size:10px}.metric-card>strong{overflow-wrap:anywhere;font-size:16px}.metric-card>strong small{color:var(--soft);font-size:10px;font-weight:600}.metric-card.primary-metric{border-color:#cfe0ff;background:linear-gradient(140deg,#f2f7ff,#fff)}.primary-metric>strong{color:#2258c7;font:700 21px var(--font-mono)}.metric-foot{color:var(--soft);font-size:9px}.comm-value{color:#11866b}.detail-tabs{display:flex;gap:20px;margin-top:14px;border-bottom:1px solid var(--line)}.detail-tabs button{position:relative;padding:0 1px 9px;border:0;background:none;color:var(--soft);font-size:11px;font-weight:650;cursor:pointer}.detail-tabs button.active{color:#2258c7}.detail-tabs button.active:after{position:absolute;right:0;bottom:-1px;left:0;height:2px;background:#3473ed;content:''}.tab-count{display:inline-grid;min-width:17px;height:16px;margin-left:4px;padding:0;place-items:center;font-size:9px}.data-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 22px;padding-top:5px}.data-grid>div{display:grid;grid-template-columns:minmax(105px,.75fr) 1.25fr;align-items:center;gap:9px;min-height:42px;border-bottom:1px solid #f0f2f6}.data-grid span{color:var(--soft);font-size:10px}.data-grid strong,.data-grid code{font-size:10px;overflow-wrap:anywhere}.data-grid code{color:#34445c;font-family:var(--font-mono)}.slot-list{display:flex;min-height:60px;flex-wrap:wrap;align-content:flex-start;gap:7px;padding-top:12px}.slot-list>span{padding:6px 8px;border:1px solid var(--line);border-radius:5px;font-size:10px}.slot-list>span.duplicate{border-color:#c43d31;color:#c43d31}.empty-state{display:grid;min-height:60px;place-items:center;color:var(--soft);font-size:11px}.frame-list{padding-top:7px}.frame-toolbar{display:flex;justify-content:space-between;gap:10px;padding:3px 0 8px;color:var(--soft);font-size:10px}.frame-toolbar>div{display:flex;gap:12px}.text-button{padding:2px;border:0;background:none;color:#4d6d9f;font-size:10px;font-weight:700;cursor:pointer}.frame-scroll{max-height:260px;overflow:auto}.frame-row{display:grid;grid-template-columns:78px 26px minmax(0,1fr) 160px;align-items:center;gap:8px;min-height:34px;border-bottom:1px solid #f0f2f6}.frame-row time,.frame-row span{color:var(--soft);font-size:9px}.frame-row code{overflow:hidden;color:#34445c;font:10px var(--font-mono);text-overflow:ellipsis;white-space:nowrap}.frame-row b{font:800 9px var(--font-mono)}.tx-tag{color:#c07a13}.rx-tag{color:#3570cf}.workspace-footer{display:flex;align-items:center;gap:17px;margin-top:10px;padding:8px 4px;color:var(--soft);font-size:9px}.workspace-footer>span{display:flex;align-items:center;gap:6px}.workspace-footer i{width:6px;height:6px}.modal-backdrop{position:fixed;inset:0;z-index:80;display:grid;place-items:center;padding:20px;background:#11182780}.modal{width:min(480px,100%);padding:20px;border-radius:10px;background:#fff;box-shadow:0 18px 50px #0003}.modal h2{margin:0 0 10px;font-size:17px}.modal p{color:#58677c;font-size:12px;line-height:1.6}.modal .field{display:grid;gap:6px}.modal .input{height:36px;padding:0 9px;border:1px solid var(--line);border-radius:6px}.button-row{display:flex;gap:8px}.top-gap{margin-top:12px}
.device-state:not(.online):not(.offline){background:#a8b2c0}.device-state.online{background:var(--mint)}.threshold-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;padding-top:12px}.threshold-grid>div:not(.empty-state){display:flex;align-items:center;justify-content:space-between;gap:6px;padding:9px;border:1px solid var(--line);border-radius:6px}.threshold-grid span{color:var(--soft);font-size:9px}.threshold-grid strong{white-space:nowrap;font:700 11px var(--font-mono)}.threshold-grid small{color:var(--soft);font:9px var(--font-body)}
.section-heading>div:first-child{display:grid;gap:2px}.section-heading>.heading-status{display:flex;align-items:center;gap:7px;padding:6px 9px;font-size:10px}
@media(max-width:820px){.top-row{grid-template-columns:1fr}.device-list{height:132px}.detail-titlebar{align-items:stretch;flex-direction:column}.detail-actions{flex-wrap:wrap}.detail-actions .btn{flex:1}}
@media(max-width:560px){.page-heading{align-items:flex-start;gap:10px}.page-heading h2{font-size:19px}.heading-status{gap:5px;padding:7px;font-size:10px}.connection-fields{grid-template-columns:1fr 1fr}.metric-cards{grid-template-columns:1fr 1fr}.metric-card.primary-metric{grid-column:span 2}.data-grid{grid-template-columns:1fr}.threshold-grid{grid-template-columns:1fr 1fr}.frame-row{grid-template-columns:62px 24px minmax(0,1fr)}.frame-row span{display:none}.frame-toolbar{align-items:flex-start;flex-direction:column}.workspace-footer{flex-wrap:wrap;gap:8px 14px}}
</style>
