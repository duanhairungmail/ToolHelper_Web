<script setup>
import { computed, ref } from 'vue'
import BasePanel from './BasePanel.vue'
import LogDrawer from './LogDrawer.vue'
import RemoteTool from './RemoteTool.vue'
import DatabaseTool from './DatabaseTool.vue'
import ApiValidationTool from './ApiValidationTool.vue'
import DeviceMacTool from './DeviceMacTool.vue'
import { useLogDrawer } from '@/composables/useLogDrawer'
import { useAppStore } from '@/stores/app'

const props = defineProps({ toolId: { type: String, required: true } })
const store = useAppStore()
const { expanded, lines, append, clear } = useLogDrawer(['[10:24:18] 页面已就绪'])
const connected = ref(false)
const serialText = ref('')
const serialLogs = ref([])
const rawExpanded = ref(false)
const conditions = ref([])
const conditionDialog = ref(false)
const conditionView = ref(false)
const condition = ref({ field: '', op: '=', value: '' })
const sql = ref('SELECT id, name, department\nFROM employees\nORDER BY id ASC\nLIMIT 100;')
const aesInput = ref('')
const aesOutput = ref('')
const pingTarget = ref('')
const pingTotal = ref(0)

const hasLog = computed(() => ['remote', 'database', 'api', 'nodered', 'kylin'].includes(props.toolId))

function runAction(message) {
  append(message)
  store.setStatus(`${message}成功`, 'success')
}

function sendSerial() {
  if (!connected.value) return store.setStatus('请先打开串口', 'error')
  if (!serialText.value.trim()) return store.setStatus('请输入要发送的数据', 'error')
  serialLogs.value.push(`[发送] ${serialText.value}`)
  store.setStatus('发送完成', 'success')
}

function addCondition() {
  if (!condition.value.field || !condition.value.value) return
  conditions.value.push({ ...condition.value })
  condition.value = { field: '', op: '=', value: '' }
  conditionDialog.value = false
}

function generateSql() {
  const where = conditions.value.length ? `\nWHERE ${conditions.value.map((item) => `${item.field} ${item.op} '${item.value}'`).join(' AND ')}` : ''
  sql.value = `SELECT id, name, department\nFROM employees${where}\nORDER BY id ASC\nLIMIT 100;`
}

function scanPing() {
  pingTotal.value = pingTarget.value.endsWith('/24') ? 254 : pingTarget.value ? 1 : 0
  store.setStatus(pingTotal.value ? `已载入 ${pingTotal.value} 个目标` : '请输入检测目标', pingTotal.value ? 'success' : 'error')
}
</script>

<template>
  <div class="tool-screen" :class="{ 'with-log': hasLog }" :data-od-id="`${toolId}-screen`">
    <RemoteTool v-if="toolId === 'remote'" />
    <DatabaseTool v-else-if="toolId === 'database'" />
    <ApiValidationTool v-else-if="toolId === 'api'" />
    <DeviceMacTool v-else-if="toolId === 'mac'" />

    <template v-else-if="toolId === 'serial'">
      <div class="equal-columns serial-grid"><div class="stack"><BasePanel title="连接设置"><div class="form-grid two"><label class="field"><span>串口号</span><select class="input"><option>COM3</option><option>COM5</option></select></label><label class="field"><span>波特率</span><select class="input"><option>9600</option><option>115200</option></select></label><label class="field"><span>数据位</span><select class="input"><option>8</option><option>7</option></select></label><label class="field"><span>校验位</span><select class="input"><option>无</option><option>偶校验</option></select></label></div><button class="btn btn-primary top-gap" @click="connected = !connected; store.setStatus(connected ? '串口连接已建立' : '串口已关闭', connected ? 'success' : 'idle')">{{ connected ? '关闭串口' : '打开串口' }}</button></BasePanel><BasePanel title="发送数据"><textarea v-model="serialText" class="input textarea" placeholder="输入十六进制数据"></textarea><button class="btn btn-primary top-gap" @click="sendSerial">发送</button></BasePanel></div><BasePanel title="接收日志"><template #actions><button class="btn btn-ghost" @click="serialLogs = []">清空日志</button></template><pre class="terminal-box fill">{{ serialLogs.length ? serialLogs.join('\n') : '等待串口数据' }}</pre></BasePanel></div>
    </template>

    <template v-else-if="toolId === 'modbus'">
      <div class="equal-columns"><BasePanel title="串口与协议" description="支持申弘版与南瑞怡和版 Modbus RTU。"><div class="form-grid two"><label class="field"><span>协议</span><select class="input"><option>申弘版</option><option>南瑞怡和版</option></select></label><label class="field"><span>串口号</span><select class="input"><option>COM3</option><option>COM5</option></select></label><label class="field"><span>波特率</span><select class="input"><option>9600</option><option>115200</option></select></label><label class="field"><span>设备地址</span><input class="input" value="1"></label></div><div class="button-row top-gap"><button class="btn btn-primary">打开串口</button><button class="btn btn-secondary">扫描设备</button><button class="btn btn-secondary">读取当前设备</button></div></BasePanel><BasePanel title="设备列表"><table><thead><tr><th>地址</th><th>序列号</th><th>在线</th></tr></thead><tbody><tr><td colspan="3" class="muted">打开串口后扫描站端下辖设备</td></tr></tbody></table></BasePanel></div><BasePanel title="解析结果" class="top-gap"><pre class="result-box">（未选中设备）</pre></BasePanel><section class="detail-drawer"><header class="drawer-head"><strong>原始报文</strong><div class="button-row"><button class="btn btn-ghost">清空</button><button class="btn btn-ghost" @click="rawExpanded = !rawExpanded">{{ rawExpanded ? '收起报文' : '展开报文' }}</button></div></header><div v-show="rawExpanded" class="drawer-body"><pre class="terminal-box">等待 Modbus RTU 通信...</pre></div></section>
    </template>

    <template v-else-if="toolId === 'ping'">
      <div class="equal-columns ping-grid"><BasePanel title="目标与检测" description="支持 CIDR 网段、末段范围和文本目标列表。"><label class="field"><span>网段或范围</span><input v-model="pingTarget" class="input" placeholder="192.168.1.0/24"></label><div class="button-row top-gap"><button class="btn btn-primary" @click="scanPing">扫描网段</button><button class="btn btn-secondary">导入文件</button><button class="btn btn-secondary">开始 Ping</button></div></BasePanel><div class="metric-grid"><div class="metric"><span>目标总数</span><strong>{{ pingTotal }}</strong></div><div class="metric"><span>在线</span><strong>0</strong></div><div class="metric"><span>离线</span><strong>0</strong></div><div class="metric"><span>平均延迟</span><strong>0 ms</strong></div></div></div><BasePanel title="检测结果" class="top-gap"><table><thead><tr><th>目标</th><th>状态</th><th>平均延迟</th><th>丢包率</th></tr></thead><tbody><tr><td colspan="4" class="muted">请先扫描网段或导入文件</td></tr></tbody></table></BasePanel>
    </template>

    <template v-else-if="toolId === 'sql'">
      <div class="equal-columns equal-height"><BasePanel title="SQL 生成器"><div class="form-grid two"><label class="field"><span>表名</span><input class="input" value="employees"></label><label class="field"><span>LIMIT</span><input class="input" value="100"></label></div><div class="button-row top-gap"><button class="btn btn-primary" @click="generateSql">生成 SQL</button><button class="btn btn-secondary" @click="conditionDialog = true">添加条件</button><button class="btn btn-secondary" @click="conditionView = true">查看条件</button></div><textarea v-model="sql" class="codebox fill"></textarea></BasePanel><BasePanel title="SQL 格式化"><textarea class="input textarea" value="select id,name from employees;"></textarea><div class="button-row top-gap"><button class="btn btn-primary">格式化</button><button class="btn btn-secondary">复制输出</button></div><pre class="terminal-box fill">SELECT id, name\nFROM employees;</pre></BasePanel></div>
      <div v-if="conditionDialog" class="modal-backdrop"><div class="modal"><h2>添加查询条件</h2><div class="form-grid"><label class="field"><span>字段名</span><input v-model="condition.field" class="input"></label><label class="field"><span>运算符</span><select v-model="condition.op" class="input"><option>=</option><option>!=</option><option>LIKE</option></select></label><label class="field"><span>值</span><input v-model="condition.value" class="input"></label></div><div class="button-row top-gap"><button class="btn btn-primary" @click="addCondition">添加</button><button class="btn btn-secondary" @click="conditionDialog = false">取消</button></div></div></div>
      <div v-if="conditionView" class="modal-backdrop"><div class="modal"><h2>已添加条件</h2><pre class="result-box">{{ conditions.length ? conditions.map((item, i) => `${i + 1}. ${item.field} ${item.op} ${item.value}`).join('\n') : '尚未添加条件' }}</pre><button class="btn btn-secondary top-gap" @click="conditionView = false">关闭</button></div></div>
    </template>

    <template v-else-if="toolId === 'aes'">
      <BasePanel title="AES 参数" description="支持模式、填充、密钥长度、编码和输出格式。"><div class="form-grid"><label class="field"><span>运算模式</span><select class="input"><option>CBC</option><option>ECB</option><option>GCM</option></select></label><label class="field"><span>密钥</span><input class="input" placeholder="输入密钥 KEY"></label><label class="field"><span>偏移量 IV</span><input class="input" placeholder="输入偏移量 IV"></label></div></BasePanel><div class="equal-columns equal-height top-gap"><BasePanel title="输入"><textarea v-model="aesInput" class="input textarea fill"></textarea><div class="button-row top-gap"><button class="btn btn-primary" @click="aesOutput = btoa(unescape(encodeURIComponent(aesInput)))">加密</button><button class="btn btn-secondary">解密</button></div></BasePanel><BasePanel title="输出结果"><template #actions><button class="btn btn-ghost" @click="aesOutput = ''">清空</button></template><textarea v-model="aesOutput" class="codebox fill" readonly></textarea></BasePanel></div>
    </template>

    <template v-else>
      <BasePanel :title="toolId === 'nodered' ? 'Node-RED 便携运行时' : toolId === 'api' ? '连接与认证' : '功能控制台'"><div class="form-grid"><label class="field"><span>目标地址</span><input class="input" placeholder="输入目标地址"></label><label class="field"><span>端口</span><input class="input" value="8080"></label></div><div class="button-row top-gap"><button class="btn btn-primary" @click="runAction('执行')">执行</button><button class="btn btn-secondary">刷新</button></div></BasePanel><BasePanel title="结果详情"><div class="empty-state">尚未执行操作，结果将在这里显示。</div></BasePanel><LogDrawer v-if="hasLog" v-model:expanded="expanded" :lines="lines" @clear="clear" /></template>
  </div>
</template>
