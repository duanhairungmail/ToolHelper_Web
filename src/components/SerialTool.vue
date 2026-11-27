<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BasePanel from './BasePanel.vue'
import { serialApi } from '@/api/serial'
import { bytesFromHex, formatHex, toSerialBytes } from '@/utils/serialData'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const capabilities = ref(null), ports = ref([]), selectedPort = ref('')
const session = ref(null), error = ref(null), busy = ref(false), notice = ref('')
const baudRate = ref(9600), dataBits = ref(8), stopBits = ref('1'), parity = ref('NONE'), flowControl = ref('NONE')
const mode = ref('HEX'), data = ref(''), appendCrlf = ref(false), checksum = ref('NONE')
const displayMode = ref('HEX'), autoEnabled = ref(false), intervalMs = ref(1000), autoSending = ref(false)
const autoCount = ref(0), autoStartedAt = ref(null), events = ref([]), cursor = ref(0), gap = ref(false), autoScroll = ref(true)
const logViewport = ref(null)
const textDecoder = new TextDecoder('utf-8')
let timer, disposed = false, refreshing = false
const signalController = new AbortController()
const signal = signalController.signal
const connected = computed(() => Boolean(session.value?.sessionId))
const preview = computed(() => {
  try { return formatHex(toSerialBytes({ mode: mode.value, data: data.value, appendCrlf: mode.value === 'TEXT' && appendCrlf.value, checksum: checksum.value })) }
  catch (failure) { return failure.message }
})
const previewValid = computed(() => {
  try { toSerialBytes({ mode: mode.value, data: data.value, appendCrlf: mode.value === 'TEXT' && appendCrlf.value, checksum: checksum.value }); return true }
  catch { return false }
})

function resetDecoder() { textDecoder.decode() }
function renderEvent(event) {
  const bytes = bytesFromHex(event.hex)
  let content = event.direction === 'RX' ? textDecoder.decode(bytes, { stream: true }) : new TextDecoder().decode(bytes)
  return { ...event, text: content.replaceAll('\r', '␍').replaceAll('\n', '␊') }
}
function acceptEvents(reply) {
  gap.value = reply.gap
  if (reply.gap) { events.value = []; resetDecoder(); notice.value = '接收事件超出缓存窗口，已重新同步；硬件收到情况无法据此判断' }
  for (const event of reply.events || []) events.value.push(renderEvent(event))
  if (events.value.length > 2000) events.value.splice(0, events.value.length - 2000)
  cursor.value = reply.cursor
  session.value = reply.session
  autoSending.value = Boolean(reply.session?.autoSending)
  if (!autoSending.value) autoStartedAt.value = null
}
async function refresh() {
  if (disposed || refreshing) return
  refreshing = true
  try {
    capabilities.value = await serialApi.capabilities(signal)
    if (disposed) return
    session.value = await serialApi.session(signal)
    if (session.value) {
      const reply = await serialApi.events(session.value.sessionId, cursor.value, signal)
      if (!disposed) acceptEvents(reply)
    } else {
      ports.value = await serialApi.ports(signal)
      if (!selectedPort.value || !ports.value.some(item => item.name === selectedPort.value)) selectedPort.value = ports.value[0]?.name || ''
      events.value = []; cursor.value = 0; autoSending.value = false
    }
    error.value = null
  } catch (failure) { if (!disposed && failure.name !== 'AbortError') error.value = failure }
  finally {
    refreshing = false
    clearTimeout(timer)
    if (!disposed) timer = setTimeout(refresh, connected.value ? (capabilities.value?.eventPollIntervalMs || 200) : 5000)
  }
}
async function perform(action) {
  if (busy.value) return
  busy.value = true; error.value = null; notice.value = ''
  let operationFailure = null
  try { await action(); store.setStatus('串口操作完成', 'success') }
  catch (failure) { operationFailure = failure; error.value = failure; store.setStatus(failure.message || '串口操作失败', 'error') }
  finally {
    busy.value = false
    if (!disposed) await refresh()
    // 刷新状态不能证明丢失响应的写请求没有到达设备，保留结果不确定提示，禁止诱导用户盲目重发。
    if (!disposed && operationFailure?.code === 'REQUEST_OUTCOME_UNKNOWN') error.value = operationFailure
  }
}
function refreshPorts() { return perform(async () => { session.value = await serialApi.session(signal); if (session.value) return; ports.value = await serialApi.ports(signal); selectedPort.value ||= ports.value[0]?.name || ''; notice.value = `已发现 ${ports.value.length} 个串口` }) }
function openPort() { return perform(async () => {
  if (!selectedPort.value) throw new Error('请先刷新并选择串口')
  session.value = await serialApi.open({ port: selectedPort.value, baudRate: baudRate.value, dataBits: dataBits.value, stopBits: stopBits.value, parity: parity.value, flowControl: flowControl.value }, signal)
  cursor.value = 0; events.value = []; resetDecoder(); notice.value = `已打开 ${session.value.port}`
}) }
function closePort() { return perform(async () => { await serialApi.close(session.value.sessionId, signal); session.value = null; autoSending.value = false; events.value = []; cursor.value = 0; resetDecoder(); notice.value = '串口已关闭' }) }
function writeOnce() { return perform(async () => {
  const body = { mode: mode.value, data: data.value, appendCrlf: mode.value === 'TEXT' && appendCrlf.value, checksum: checksum.value }
  // 服务端是最终校验边界；本地预览仅用于让用户确认即将发送的字节。
  toSerialBytes(body)
  await serialApi.write(session.value.sessionId, body, signal)
}) }
function startAuto() { return perform(async () => {
  const body = { mode: mode.value, data: data.value, appendCrlf: mode.value === 'TEXT' && appendCrlf.value, checksum: checksum.value }
  toSerialBytes(body)
  session.value = await serialApi.startAuto(session.value.sessionId, { write: body, intervalMs: Number(intervalMs.value) }, signal)
  autoSending.value = true; autoStartedAt.value = new Date(); autoCount.value = 0
}) }
function stopAuto() { return perform(async () => { session.value = await serialApi.stopAuto(session.value.sessionId, signal); autoSending.value = false; autoStartedAt.value = null }) }
function clearEvents() { events.value = []; notice.value = '当前页面日志已清空；串口连接未改变' }
function formatTime(value) { return new Date(value).toLocaleTimeString('zh-CN', { hour12: false, fractionalSecondDigits: 3 }) }
function exportTxt() {
  const lines = events.value.map(item => {
    const value = displayMode.value === 'HEX' ? item.hex : item.text
    return `${new Date(item.at).toLocaleDateString('zh-CN')} ${formatTime(item.at)} [${item.direction}] ${value} (${item.byteCount} B, ${item.status})`
  })
  const blob = new Blob([`ToolHelper 串口日志\r\n${lines.join('\r\n')}`], { type: 'text/plain;charset=utf-8' })
  const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = `serial-${new Date().toISOString().replaceAll(':', '-')}.txt`; link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000)
}
function eventText(item) { return displayMode.value === 'HEX' ? item.hex : item.text }
watch(() => events.value.length, async () => {
  if (!autoScroll.value) return
  await nextTick()
  if (logViewport.value) logViewport.value.scrollTop = logViewport.value.scrollHeight
}, { deep: false })

onMounted(refresh)
onBeforeUnmount(() => {
  const activeSessionId = session.value?.sessionId
  // 页面离开时停止周期发送并尽力释放端口；请求不重试，避免误重放TX。
  if (activeSessionId) serialApi.close(activeSessionId).catch(() => {})
  disposed = true; clearTimeout(timer); signalController.abort()
})
</script>

<template>
  <BasePanel title="串口连接" description="操作对象是运行 Java 本机服务的这台电脑。每次写入都会真实发送到所选端口。">
    <template #actions><span class="badge" :class="connected ? '' : 'warn'" role="status">{{ connected ? `已连接 ${session.port}` : capabilities ? '未连接' : '服务不可用' }}</span></template>
    <div class="serial-settings">
      <label class="field"><span>串口</span><select v-model="selectedPort" class="input" :disabled="connected"><option value="">请选择串口</option><option v-for="port in ports" :key="port.name" :value="port.name">{{ port.name }}{{ port.description ? ` · ${port.description}` : '' }}</option></select></label>
      <label class="field"><span>波特率</span><select v-model.number="baudRate" class="input" :disabled="connected"><option v-for="rate in [1200,2400,4800,9600,19200,38400,57600,115200,230400,460800,921600]" :key="rate" :value="rate">{{ rate }}</option></select></label>
      <label class="field"><span>数据位</span><select v-model.number="dataBits" class="input" :disabled="connected"><option v-for="bits in [5,6,7,8]" :key="bits" :value="bits">{{ bits }}</option></select></label>
      <label class="field"><span>停止位</span><select v-model="stopBits" class="input" :disabled="connected"><option value="1">1</option><option value="1.5">1.5</option><option value="2">2</option></select></label>
      <label class="field"><span>校验位</span><select v-model="parity" class="input" :disabled="connected"><option value="NONE">无</option><option value="ODD">奇</option><option value="EVEN">偶</option><option value="MARK">标记</option><option value="SPACE">空格</option></select></label>
      <label class="field"><span>流控</span><select v-model="flowControl" class="input" :disabled="connected"><option value="NONE">无</option><option value="XON_XOFF">XON/XOFF</option><option value="RTS_CTS">RTS/CTS</option></select></label>
    </div>
    <div class="button-row top-gap"><button v-if="!connected" class="btn btn-secondary" :disabled="busy" @click="refreshPorts">刷新串口</button><button v-if="!connected" class="btn btn-primary" :disabled="busy || !selectedPort" @click="openPort">打开串口</button><button v-else class="btn btn-secondary" :disabled="busy" @click="closePort">关闭串口</button><button class="btn btn-ghost" :disabled="busy" @click="refresh">刷新状态</button><span v-if="session" class="muted">TX {{ session.txBytes }} B · RX {{ session.rxBytes }} B · 发送 {{ session.transmissions }} 次</span></div>
    <p v-if="!ports.length && !connected && capabilities" class="muted">未发现串口。连接适配器后刷新；不需要为此安装本工具提供的驱动。</p>
    <p v-if="notice" class="serial-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="validation-error" role="alert">{{ error.message }} <small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
  </BasePanel>

  <div class="serial-workspace top-gap">
    <BasePanel title="发送数据" description="校验默认关闭。HEX奇数位会拒绝发送；文本CRLF仅在文本模式显式勾选。">
      <div class="serial-settings serial-send-settings">
        <label class="field"><span>数据格式</span><select v-model="mode" class="input"><option value="HEX">HEX</option><option value="TEXT">文本 UTF-8</option></select></label>
        <label class="field"><span>追加校验</span><select v-model="checksum" class="input"><option value="NONE">关闭</option><option value="MODBUS_CRC16">Modbus CRC16</option><option value="CRC16_CCITT">CRC16-CCITT</option><option value="CRC32">CRC32</option><option value="LRC">LRC</option><option value="XOR">XOR</option></select></label>
        <label v-if="mode === 'TEXT'" class="serial-check"><input v-model="appendCrlf" type="checkbox"> 追加 CRLF</label>
      </div>
      <textarea v-model="data" class="input textarea top-gap" :placeholder="mode === 'HEX' ? '例如：01 03 00 00 00 0A' : '输入UTF-8文本'" :disabled="!connected || autoSending" :maxlength="mode === 'HEX' ? 16384 : 4096"></textarea>
      <div class="serial-preview"><span>实际发送预览（HEX）</span><code>{{ preview }}</code></div>
      <div class="button-row top-gap"><button class="btn btn-primary" :disabled="!connected || busy || autoSending || !previewValid" @click="writeOnce">发送一次</button><label class="serial-check"><input v-model="autoEnabled" type="checkbox" :disabled="!connected || autoSending"> 定时发送</label><label class="field serial-interval"><span>周期（ms）</span><input v-model.number="intervalMs" class="input" type="number" min="100" max="86400000" step="100" :disabled="!autoEnabled || !connected || autoSending"></label><button v-if="!autoSending" class="btn btn-secondary" :disabled="!autoEnabled || !connected || busy || !previewValid" @click="startAuto">启动定时发送</button><button v-else class="btn btn-secondary" :disabled="busy" @click="stopAuto">停止定时发送</button></div>
      <p v-if="autoSending" class="serial-notice" role="status">定时发送运行中 · 周期 {{ session?.autoSendIntervalMs }} ms · 累计 {{ session?.transmissions }} 次</p>
    </BasePanel>

    <BasePanel title="收发日志" description="串口按字节流接收，显示块不代表设备报文边界。">
      <template #actions><div class="button-row"><select v-model="displayMode" class="input serial-display-select" aria-label="日志显示格式"><option value="HEX">HEX</option><option value="TEXT">UTF-8文本</option></select><label class="serial-check"><input v-model="autoScroll" type="checkbox"> 自动滚动</label><button class="btn btn-ghost" :disabled="!events.length" @click="exportTxt">导出 TXT</button><button class="btn btn-ghost" @click="clearEvents">清空日志</button></div></template>
      <p v-if="gap" class="validation-warning" role="alert">事件队列发生缺口，当前显示已重新同步。</p>
      <div ref="logViewport" class="serial-log" aria-live="polite"><div v-for="item in events" :key="item.cursor" class="serial-log-row" :class="item.direction.toLowerCase()"><time>{{ formatTime(item.at) }}</time><strong>{{ item.direction }}</strong><code>{{ eventText(item) || '（文本解码暂未产生完整字符）' }}</code><span>{{ item.byteCount }} B · {{ item.status }}</span></div><div v-if="!events.length" class="empty-state">{{ connected ? '等待串口数据' : '打开串口后显示收发日志' }}</div></div>
    </BasePanel>
  </div>
</template>

<style scoped>
.serial-settings { display: grid; grid-template-columns: repeat(6, minmax(100px, 1fr)); gap: 10px; }
.serial-workspace { display: grid; gap: 16px; grid-template-columns: minmax(0, .85fr) minmax(0, 1.15fr); }
.serial-workspace > .panel { min-width: 0; }
.serial-send-settings { grid-template-columns: repeat(2, minmax(140px, 1fr)); }
.serial-check { display: inline-flex; align-items: center; gap: 7px; min-height: 44px; }
.serial-interval { width: 145px; }
.serial-preview { display: grid; gap: 4px; margin-top: 10px; padding: 10px; background: var(--bg); border: 1px solid var(--border); border-radius: 6px; }
.serial-preview span, .serial-log-row span { color: var(--muted); font-size: 11px; }
.serial-preview code, .serial-log-row code { font: 12px/1.6 var(--font-mono); overflow-wrap: anywhere; }
.serial-log { display: flex; min-height: 390px; max-height: 58vh; flex-direction: column; gap: 5px; overflow: auto; padding: 8px; background: #172033; border-radius: 6px; color: #edf1f6; }
.serial-log-row { display: grid; grid-template-columns: 90px 35px minmax(0, 1fr) 90px; gap: 8px; align-items: baseline; padding: 4px 6px; border-bottom: 1px solid #344055; }
.serial-log-row time { color: #a9b6c9; font: 11px var(--font-mono); }
.serial-log-row.rx strong { color: #8bd5ca; }
.serial-log-row.tx strong { color: #f6c177; }
.serial-display-select { width: auto; min-height: 38px; }
.serial-notice { margin: 8px 0 0; color: var(--muted); }
.validation-error, .validation-warning { color: var(--danger); overflow-wrap: anywhere; }
@media (max-width: 1100px) { .serial-settings { grid-template-columns: repeat(3, minmax(100px, 1fr)); } .serial-workspace { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .serial-settings, .serial-send-settings { grid-template-columns: repeat(2, minmax(0, 1fr)); } .serial-log-row { grid-template-columns: 65px 30px minmax(0, 1fr); } .serial-log-row span { grid-column: 3; } }
</style>
