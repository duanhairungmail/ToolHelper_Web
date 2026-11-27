<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BasePanel from './BasePanel.vue'
import { modbusApi } from '@/api/modbus'

const protocols = [{ id: 'SHEN_HONG', name: '申弘版' }, { id: 'NAN_RUI_YI_HE', name: '南瑞怡和版' }]
const ports = ref([]), selectedPort = ref(''), protocol = ref('SHEN_HONG'), baudRate = ref(9600)
const session = ref(null), events = ref([]), cursor = ref(0), selectedAddress = ref(''), polling = ref(false)
const error = ref(''), notice = ref(''), busy = ref(false), resetDialog = ref(false), resetAddress = ref('')
let disposed = false, timer, heartbeatTimer, controller = new AbortController(), inFlight = false
const connected = computed(() => Boolean(session.value?.sessionId))
const selectedDevice = computed(() => session.value?.devices?.find(item => item.address === Number(selectedAddress.value)) || null)
const isRunning = computed(() => ['RUNNING', 'QUEUED'].includes(session.value?.operationStatus))

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
function chooseDevice(device) { selectedAddress.value = String(device.address) }
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
  <BasePanel title="串口与协议" description="操作的是运行 Java 本机服务的电脑。默认9600、8/N/1；扫描和读取仅在你点击后执行。">
    <template #actions><span class="badge" :class="connected ? '' : 'warn'" role="status">{{ connected ? `已连接 ${session.port}` : '未连接' }}</span></template>
    <div class="modbus-settings">
      <label class="field"><span>协议</span><select v-model="protocol" class="input" :disabled="connected"><option v-for="item in protocols" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label class="field"><span>串口</span><select v-model="selectedPort" class="input" :disabled="connected"><option value="">请选择</option><option v-for="port in ports" :key="port.name" :value="port.name">{{ port.name }}{{ port.description ? ` · ${port.description}` : '' }}</option></select></label>
      <label class="field"><span>波特率</span><select v-model.number="baudRate" class="input" :disabled="connected"><option v-for="rate in [1200,2400,4800,9600,19200,38400,57600,115200,230400,460800,921600]" :key="rate" :value="rate">{{ rate }}</option></select></label>
      <label class="field"><span>设备地址</span><input v-model="selectedAddress" class="input" inputmode="numeric" placeholder="1-255" :disabled="!connected || isRunning || polling"></label>
    </div>
    <div class="button-row top-gap">
      <button v-if="!connected" class="btn btn-secondary" :disabled="busy" @click="refreshPorts">刷新串口</button>
      <button v-if="!connected" class="btn btn-primary" :disabled="busy || !selectedPort" @click="openPort">打开串口</button>
      <button v-else class="btn btn-secondary" :disabled="busy" @click="closePort">关闭串口</button>
      <button class="btn btn-secondary" :disabled="!connected || busy || isRunning || polling" @click="startOperation('SCAN')">扫描设备</button>
      <button class="btn btn-secondary" :disabled="!connected || busy || isRunning || polling" @click="readSelected">读取当前设备</button>
      <button class="btn btn-secondary" :disabled="!connected || busy || (isRunning && !polling)" @click="togglePolling">{{ polling ? '停止轮询（当前设备完成后停止）' : '开始轮询' }}</button>
      <button v-if="protocol === 'NAN_RUI_YI_HE'" class="btn btn-danger" :disabled="!connected || busy || isRunning || polling" @click="resetAddress = selectedAddress; resetDialog = true">复位设备</button>
    </div>
    <p class="muted top-gap">状态：{{ session?.operationMessage || '空闲' }}<template v-if="polling"> · 每 {{ session?.pollIntervalMs }} ms 逐台读取</template></p>
    <p v-if="notice" class="serial-notice" role="status">{{ notice }}</p><p v-if="error" class="validation-error" role="alert">{{ error }}</p>
  </BasePanel>

  <BasePanel title="下辖设备" description="站端响应、站端报告的通讯状态和本轮读取结果分别显示。" class="top-gap">
    <div class="modbus-table-wrap"><table class="modbus-table"><thead><tr><th>地址</th><th>序列号 / ID</th><th>报警</th><th>故障</th><th>离子值</th><th>站端通讯状态</th><th>本轮读取</th><th>更新时间</th></tr></thead>
      <tbody><tr v-for="device in session?.devices || []" :key="device.address" :class="{ selected: Number(selectedAddress) === device.address }" @click="chooseDevice(device)"><td>{{ device.address }}</td><td><code>{{ device.serialText || device.serialHex || '未读取' }}</code></td><td>{{ device.alarmName }}</td><td>{{ device.faults?.join('、') || (device.faultHex ? '无' : '未读取') }}</td><td>{{ device.ionValue ?? '未读取' }}</td><td>{{ device.commStatusText || '未获取' }}</td><td>{{ device.readStatus }}<small v-if="device.readMessage"> · {{ device.readMessage }}</small></td><td>{{ device.updatedAt ? new Date(device.updatedAt).toLocaleTimeString() : '—' }}</td></tr>
        <tr v-if="!session?.devices?.length"><td colspan="8" class="muted">{{ connected ? '请扫描设备，或输入地址读取单台设备' : '打开串口后扫描站端下辖设备' }}</td></tr></tbody></table></div>
  </BasePanel>

  <BasePanel title="站端地址槽位" description="保留16个原始位置，用于核对地址与站端通讯状态的对应关系。" class="top-gap">
    <div class="modbus-slots"><span v-for="slot in session?.positions || []" :key="slot.index" :class="{ duplicate: slot.duplicate }">#{{ slot.index }} · {{ slot.address || '空' }}<template v-if="slot.communicationState !== null"> · 通讯 {{ slot.communicationState === 1 ? '正常' : slot.communicationState === 0 ? '异常' : slot.communicationState }}</template><template v-if="slot.duplicate"> · 重复</template></span><div v-if="!session?.positions?.length" class="empty-state">扫描后显示站端原始位置</div></div>
  </BasePanel>

  <BasePanel title="设备解析详情" class="top-gap">
    <div v-if="selectedDevice" class="modbus-detail"><div><span>设备地址</span><strong>{{ selectedDevice.address }}</strong></div><div><span>报警级别</span><strong>{{ selectedDevice.alarmName }}</strong></div><div><span>工作状态</span><strong>{{ selectedDevice.workStatusText }}</strong></div><div><span>通讯状态</span><strong>{{ selectedDevice.commStatusText }}</strong></div><div><span>通讯地址</span><strong>{{ selectedDevice.deviceAddress ?? '未读取' }}</strong></div><div><span>设备ID原始HEX</span><code>{{ selectedDevice.serialHex || '未读取' }}</code></div><div><span>ID映射</span><strong>{{ selectedDevice.serialMapping || '未读取' }}</strong></div><div><span>故障字</span><code>{{ selectedDevice.faultHex || '未读取' }}</code></div><div><span>故障明细</span><strong>{{ selectedDevice.faultHex ? (selectedDevice.faults?.join('、') || '无') : '未读取' }}</strong></div><div v-for="item in selectedDevice.thresholds" :key="item.name"><span>{{ item.name }}</span><strong>{{ item.value }}</strong></div></div>
    <div v-else class="empty-state">选择设备或读取地址后显示解析数据。</div>
  </BasePanel>

  <BasePanel title="原始报文" description="HEX报文保存在有界内存中；页面离开后会话关闭，报文不写入持久存储。" class="top-gap">
    <template #actions><div class="button-row"><button class="btn btn-ghost" :disabled="!events.length" @click="exportTxt">导出 TXT</button><button class="btn btn-ghost" :disabled="!events.length" @click="clearEvents">清空</button></div></template>
    <div class="modbus-log"><div v-for="event in events" :key="event.cursor" class="modbus-log-row"><time>{{ new Date(event.at).toLocaleTimeString() }}</time><strong>{{ event.direction }}</strong><code>{{ event.hex || event.note }}</code><span>{{ event.verification }}</span></div><div v-if="!events.length" class="empty-state">等待操作报文</div></div>
  </BasePanel>

  <div v-if="resetDialog" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="reset-title"><h2 id="reset-title">确认复位设备</h2><p>将通过 {{ session?.port }} 向南瑞怡和设备 {{ resetAddress }} 发送一次复位命令。这会复位设备报警状态；超时后结果可能未知，系统不会自动重发。</p><label class="field"><span>复位目标地址</span><input v-model="resetAddress" class="input" inputmode="numeric"></label><div class="button-row top-gap"><button class="btn btn-danger" @click="requestReset">确认发送一次</button><button class="btn btn-secondary" @click="resetDialog = false">取消</button></div></section></div>
</template>

<style scoped>
.modbus-settings { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 12px; }
.modbus-table-wrap { overflow-x: auto; } .modbus-table { width: 100%; border-collapse: collapse; min-width: 900px; }
.modbus-table th, .modbus-table td { padding: 9px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
.modbus-table tbody tr { cursor: pointer; } .modbus-table tbody tr:hover, .modbus-table tbody tr.selected { background: color-mix(in srgb, var(--primary) 9%, transparent); }
.modbus-table code, .modbus-detail code, .modbus-log code { font: 12px/1.5 var(--font-mono); overflow-wrap: anywhere; }
.modbus-detail { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.modbus-detail > div { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--border); border-radius: 6px; min-width: 0; }
.modbus-detail span, .modbus-table small, .modbus-log span { color: var(--muted); font-size: 12px; }
.modbus-slots { display: flex; flex-wrap: wrap; gap: 8px; } .modbus-slots > span { padding: 6px 9px; border: 1px solid var(--border); border-radius: 5px; } .modbus-slots > span.duplicate { border-color: var(--danger); color: var(--danger); }
.modbus-log { display: flex; max-height: 380px; min-height: 120px; flex-direction: column; overflow: auto; gap: 4px; padding: 8px; background: #172033; color: #edf1f6; border-radius: 6px; }
.modbus-log-row { display: grid; grid-template-columns: 85px 44px minmax(0, 1fr) 210px; gap: 8px; padding: 4px; border-bottom: 1px solid #344055; align-items: baseline; }
.serial-notice { color: var(--muted); } .validation-error { color: var(--danger); }
@media(max-width:900px) { .modbus-settings { grid-template-columns: repeat(2, minmax(0, 1fr)); } .modbus-detail { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media(max-width:620px) { .modbus-log-row { grid-template-columns: 65px 38px minmax(0, 1fr); } .modbus-log-row span { grid-column: 3; } }
</style>
