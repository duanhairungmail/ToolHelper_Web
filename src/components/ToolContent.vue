<script setup>
import { computed, ref } from 'vue'
import BasePanel from './BasePanel.vue'
import LogDrawer from './LogDrawer.vue'
import RemoteTool from './RemoteTool.vue'
import DatabaseTool from './DatabaseTool.vue'
import ApiValidationTool from './ApiValidationTool.vue'
import DeviceMacTool from './DeviceMacTool.vue'
import DruidScanTool from './DruidScanTool.vue'
import KylinOpsTool from './KylinOpsTool.vue'
import SerialTool from './SerialTool.vue'
import ModbusTool from './ModbusTool.vue'
import PingTool from './PingTool.vue'
import CronTool from './CronTool.vue'
import SqlTool from './SqlTool.vue'
import AesTool from './AesTool.vue'
import { useLogDrawer } from '@/composables/useLogDrawer'
import { useAppStore } from '@/stores/app'

const props = defineProps({ toolId: { type: String, required: true } })
const store = useAppStore()
const { expanded, lines, append, clear } = useLogDrawer(['[10:24:18] 页面已就绪'])
const rawExpanded = ref(false)

const hasLog = computed(() => ['remote', 'database', 'api', 'nodered'].includes(props.toolId))

function runAction(message) {
  append(message)
  store.setStatus(`${message}成功`, 'success')
}

</script>

<template>
  <div class="tool-screen" :class="{ 'with-log': hasLog }" :data-od-id="`${toolId}-screen`">
    <RemoteTool v-if="toolId === 'remote'" />
    <DatabaseTool v-else-if="toolId === 'database'" />
    <ApiValidationTool v-else-if="toolId === 'api'" />
    <DeviceMacTool v-else-if="toolId === 'mac'" />
    <DruidScanTool v-else-if="toolId === 'druid'" />
    <KylinOpsTool v-else-if="toolId === 'kylin'" />

    <SerialTool v-else-if="toolId === 'serial'" />

    <ModbusTool v-else-if="toolId === 'modbus'" />

    <template v-else-if="toolId === 'modbus-placeholder'">
      <div class="equal-columns"><BasePanel title="串口与协议" description="支持申弘版与南瑞怡和版 Modbus RTU。"><div class="form-grid two"><label class="field"><span>协议</span><select class="input"><option>申弘版</option><option>南瑞怡和版</option></select></label><label class="field"><span>串口号</span><select class="input"><option>COM3</option><option>COM5</option></select></label><label class="field"><span>波特率</span><select class="input"><option>9600</option><option>115200</option></select></label><label class="field"><span>设备地址</span><input class="input" value="1"></label></div><div class="button-row top-gap"><button class="btn btn-primary">打开串口</button><button class="btn btn-secondary">扫描设备</button><button class="btn btn-secondary">读取当前设备</button></div></BasePanel><BasePanel title="设备列表"><table><thead><tr><th>地址</th><th>序列号</th><th>在线</th></tr></thead><tbody><tr><td colspan="3" class="muted">打开串口后扫描站端下辖设备</td></tr></tbody></table></BasePanel></div><BasePanel title="解析结果" class="top-gap"><pre class="result-box">（未选中设备）</pre></BasePanel><section class="detail-drawer"><header class="drawer-head"><strong>原始报文</strong><div class="button-row"><button class="btn btn-ghost">清空</button><button class="btn btn-ghost" @click="rawExpanded = !rawExpanded">{{ rawExpanded ? '收起报文' : '展开报文' }}</button></div></header><div v-show="rawExpanded" class="drawer-body"><pre class="terminal-box">等待 Modbus RTU 通信...</pre></div></section>
    </template>

    <PingTool v-else-if="toolId === 'ping'" />
    <CronTool v-else-if="toolId === 'cron'" />

    <SqlTool v-else-if="toolId === 'sql'" />

    <AesTool v-else-if="toolId === 'aes'" />

    <template v-else>
      <BasePanel :title="toolId === 'nodered' ? 'Node-RED 便携运行时' : toolId === 'api' ? '连接与认证' : '功能控制台'"><div class="form-grid"><label class="field"><span>目标地址</span><input class="input" placeholder="输入目标地址"></label><label class="field"><span>端口</span><input class="input" value="8080"></label></div><div class="button-row top-gap"><button class="btn btn-primary" @click="runAction('执行')">执行</button><button class="btn btn-secondary">刷新</button></div></BasePanel><BasePanel title="结果详情"><div class="empty-state">尚未执行操作，结果将在这里显示。</div></BasePanel><LogDrawer v-if="hasLog" v-model:expanded="expanded" :lines="lines" @clear="clear" /></template>
  </div>
</template>
