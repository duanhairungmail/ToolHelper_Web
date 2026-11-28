<script setup>
import { computed, ref, watch } from 'vue'
import { meterResponse } from '../utils/simulatedMeter.js'

const props = defineProps({
  meters: { type: Object, required: true },
  sessions: { type: Array, default: () => [] },
  busy: Boolean,
  supported: Boolean,
  dirty: { type: Function, required: true },
  gateways: { type: Array, required: true },
  bindings: { type: Object, required: true },
  devices: { type: Array, required: true },
  locked: { type: Boolean, default: false }
})
const emit = defineEmits(['bind', 'edit', 'apply'])
const bindingOpen = ref(false)
const targetGatewayKey = ref('')

// 测量草稿由共享编辑器持有，运行会话只在点击应用后更新。
const selectedType = ref('dc')
const activeMeter = computed(() => props.meters[selectedType.value])
const response = computed(() => {
  try { return { hex: meterResponse(selectedType.value, activeMeter.value), error: '' } }
  catch (error) { return { hex: '', error: error.message } }
})
const boundSession = computed(() => props.sessions.find(s => s.gateway.key === boundGateway.value?.key))
const boundGateway = computed(() => props.gateways.find((gateway) => gateway.key === props.bindings[selectedType.value]))
const displacedDeviceCount = computed(() => props.devices.filter((device) => targetGatewayKey.value && device.gatewayKey === targetGatewayKey.value).length)
const meterDownTopic = 'firetopic/__meterTopicKey/down'
const meterUpTopic = 'firetopic/__meterTopicKey/up'

function openBinding() {
  targetGatewayKey.value = boundGateway.value?.key || props.gateways[0]?.key || ''
  bindingOpen.value = true
}

function confirmBinding() {
  if (props.locked || !props.gateways.some((gateway) => gateway.key === targetGatewayKey.value)) return
  emit('bind', selectedType.value, targetGatewayKey.value)
  bindingOpen.value = false
}

watch(selectedType, () => { bindingOpen.value = false })
watch(() => props.locked, (locked) => { if (locked) bindingOpen.value = false })
</script>

<template>
  <section class="meter-card" aria-label="模拟电表">
    <header class="meter-heading">
      <span class="meter-icon" aria-hidden="true">ϟ</span>
      <div><h3>模拟电表</h3><p>直流 / 单相交流 · 独立电表主题</p></div>
      <span class="preview-badge">旧监测程序兼容编码</span>
    </header>

    <div class="meter-tabs" role="tablist" aria-label="电表类型">
      <button v-for="(meter, type) in meters" :id="`meter-tab-${type}`" :key="type" type="button" role="tab" :aria-selected="selectedType === type" aria-controls="meter-editor" :class="{ selected: selectedType === type }" @click="selectedType = type">
        <strong>{{ meter.name }}</strong><span>{{ meter.model }} · {{ gateways.find(gateway => gateway.key === bindings[type])?.name || '未绑定' }}</span>
      </button>
    </div>

    <div id="meter-editor" role="tabpanel" :aria-labelledby="`meter-tab-${selectedType}`">
      <div class="meter-identity"><strong>{{ activeMeter.model }}</strong><span>{{ boundSession ? `运行版本 ${boundSession.version}` : '本地数据草稿' }}</span></div>
      <div class="meter-binding">
        <div class="binding-status"><div><strong>{{ boundGateway ? `已绑定 ${boundGateway.name}` : '尚未绑定网关' }}</strong><small v-if="boundGateway">{{ boundGateway.gatewayId }} · {{ boundGateway.brokerUrl || '未填写 Broker' }}</small></div><button type="button" class="binding-button" :disabled="locked" @click="openBinding">{{ boundGateway ? '更换网关' : '绑定网关' }}</button><button v-if="boundGateway" type="button" class="binding-button" :disabled="locked" @click="emit('bind', selectedType, '')">解除绑定</button></div>
        <div v-if="bindingOpen" class="binding-picker">
          <label>选择网关<select v-model="targetGatewayKey" aria-label="电表绑定网关"><option v-for="gateway in gateways" :key="gateway.key" :value="gateway.key">{{ gateway.name }} · {{ gateway.gatewayId }}（{{ Object.values(bindings).filter(key => key === gateway.key).length }} 块电表）</option></select></label>
          <p>绑定后将自动解除此网关的 {{ displacedDeviceCount }} 台极早期设备绑定，保留设备数据。两块电表可共用一个网关。</p>
          <div class="binding-actions"><button type="button" class="binding-button primary" :disabled="locked || !targetGatewayKey" @click="confirmBinding">确认绑定</button><button type="button" class="binding-button" @click="bindingOpen = false">取消</button></div>
        </div>
        <p v-if="locked" class="binding-lock">请等待会话状态确认，并停止全部运行网关后再修改绑定。</p>
      </div>
      <div class="meter-topics">
        <div><span class="topic-tag sub">SUB</span><code>{{ meterDownTopic }}</code></div>
        <div><span class="topic-tag pub">PUB</span><code>{{ meterUpTopic }}</code></div>
        <p>电表使用固定主题，Broker 取自绑定网关；同一网关不能同时绑定电表和极早期设备。</p>
      </div>

      <div class="meter-fields">
        <label v-for="field in activeMeter.fields" :key="`${selectedType}-${field.key}`" class="meter-field">
          <span>{{ field.label }}<small>{{ field.address }}</small></span>
          <div class="meter-input"><input  :value="field.value" @input="emit('edit', selectedType, field.key, $event.target.value)" type="number" :step="field.step" :aria-label="`${activeMeter.model} ${field.label}`"><span v-if="field.unit">{{ field.unit }}</span></div>
        </label>
      </div>
      <label v-if="selectedType === 'dc'" class="meter-field"><span>波特率原始特征字（0–65535）</span><input :value="activeMeter.baudRateRaw" type="number" min="0" max="65535" @input="emit('edit', 'dc', 'baudRateRaw', $event.target.value)"></label>
      <div class="meter-query"><span>部署包固定查询 · 从站 {{ activeMeter.address }} · FC 03</span><code>{{ activeMeter.query }}</code></div>
      <div class="meter-query"><span>响应草稿预览 · 实际响应以运行版本为准</span><code v-if="!response.error">{{ response.hex }}</code><p v-else role="alert">{{ response.error }}</p></div>
      <div class="binding-actions"><button class="binding-button primary" :disabled="busy || !supported || boundSession?.state !== 'RUNNING' || !!response.error" @click="emit('apply', boundGateway.key)">应用当前网关电表数据</button><span class="meter-note">{{ boundSession ? (dirty(boundGateway.key) ? '当前网关有未应用修改' : '当前网关数据已应用') : '连接绑定网关后可应用数据' }}</span></div>
      <p v-if="!supported" class="meter-note">当前后端尚未声明支持电表，请更新并启动 Java 服务。</p>
      <p class="meter-note">兼容旧程序 signed16 × 256 解析，非厂家标准字序。直流波特率特征字默认 0 为占位，不能认定为 9600；交流 0x000D 兼容补零。切换工具保留草稿，刷新可恢复仍运行的快照，未应用修改不恢复。</p>
    </div>
  </section>
</template>

<style scoped>
.meter-binding{margin-bottom:10px;padding:10px;border:1px solid #e5e9f0;border-radius:6px}.binding-status{display:flex;flex-wrap:wrap;align-items:center;gap:6px}.binding-status>div{display:grid;min-width:0;flex:1;gap:4px;overflow-wrap:anywhere}.binding-status strong{font-size:10px;color:#587397}.binding-status small{font-size:9px;color:#93a1b4}.binding-button{padding:5px 8px;border:1px solid #dce4ef;border-radius:5px;background:#fff;color:#587397;font-size:10px;cursor:pointer}.binding-button:disabled{opacity:.45;cursor:not-allowed}.binding-button.primary{border-color:#3473ed;background:#3473ed;color:white}.binding-picker{display:grid;gap:8px;margin-top:10px;padding-top:10px;border-top:1px solid #edf0f4}.binding-picker label{display:grid;gap:6px;font-size:10px;color:#66768e}.binding-picker select{min-width:0;width:100%;height:32px;border:1px solid #dce4ef;border-radius:5px;background:#fff;color:#455873;font-size:10px}.binding-picker p,.binding-lock{margin:0;color:#a17e46;font-size:9px;line-height:1.7}.binding-lock{margin-top:8px}.binding-actions{display:flex;gap:6px}
.meter-card{min-width:0;padding:15px;border:1px solid #e0e5ed;border-radius:8px;background:#fff;box-shadow:0 2px 7px #1c33500a;color:#26364e}
.meter-heading{display:flex;align-items:center;gap:9px}.meter-icon{display:grid;width:30px;height:30px;flex:none;place-items:center;border-radius:7px;background:#fff4e3;color:#c18a32;font-size:20px}.meter-heading h3{margin:0;font-size:12px}.meter-heading p{margin:2px 0 0;color:#98a4b4;font-size:9px}.preview-badge{margin-left:auto;padding:4px 7px;border-radius:4px;background:#fff7e9;color:#a7803b;font-size:9px;white-space:nowrap}
.meter-tabs{display:flex;gap:7px;margin:14px 0 12px}.meter-tabs button{display:grid;flex:1;min-width:0;gap:4px;padding:9px 10px;border:1px solid #e8ecf2;border-radius:6px;background:#fff;text-align:left;color:#72829a;cursor:pointer}.meter-tabs button.selected{border-color:#bfd1ef;background:#f7faff;box-shadow:0 0 0 1px #e4edfb;color:#496dab}.meter-tabs strong{font-size:10px}.meter-tabs button span{font:9px ui-monospace,monospace;color:#93a1b4}.meter-identity{display:flex;align-items:center;justify-content:space-between;margin:0 0 9px}.meter-identity strong{font:600 11px ui-monospace,monospace}.meter-identity>span{font-size:9px;color:#93a1b4}
.meter-topics{display:grid;gap:7px;padding:10px;border:1px solid #deebfa;border-radius:6px;background:#f7faff}.meter-topics>div{display:flex;align-items:center;gap:8px;min-width:0}.topic-tag{padding:4px 6px;border-radius:4px;font:700 8px ui-monospace,monospace}.sub{background:#eef2ff;color:#6474c5}.pub{background:#e8f7f1;color:#339576}.meter-topics code{color:#587397;font:10px ui-monospace,monospace;overflow-wrap:anywhere}.meter-topics p{margin:0;color:#8394aa;font-size:9px;line-height:1.6}
.meter-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:13px}.meter-field{display:grid;min-width:0;gap:5px}.meter-field>span{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:4px;color:#66768e;font-size:9px;font-weight:700}.meter-field small{color:#a1acbb;font:8px ui-monospace,monospace}.meter-input{display:flex;align-items:center;min-width:0;border:1px solid #e5e9f0;border-radius:5px;background:#fff}.meter-input:focus-within{border-color:#89a9dc;outline:2px solid #dce8fa}.meter-input input{width:100%;min-width:0;height:32px;padding:5px 8px;border:0;border-radius:5px;outline:none;background:transparent;color:#455873;font:11px ui-monospace,monospace}.meter-input>span{padding-right:8px;color:#93a1b4;font-size:9px}
.meter-query{display:grid;gap:6px;margin-top:13px;padding:9px 10px;border:1px solid #edf0f4;border-radius:6px;background:#fbfcfe}.meter-query>span{font-size:9px;color:#8796aa}.meter-query code{font:10px ui-monospace,monospace;color:#425570;overflow-wrap:anywhere}.meter-note{margin:9px 0 0;color:#a17e46;font-size:9px;line-height:1.7}
@media(max-width:420px){.meter-heading{flex-wrap:wrap}.preview-badge{margin-left:39px}.meter-fields{grid-template-columns:1fr}}
</style>
