<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import BasePanel from './BasePanel.vue'
import { useDeviceMac, macColumns } from '../composables/useDeviceMac.js'

const { rows, task, capabilities, error, notice, pending, loaded, available, selected, dirty, running, busy, canQuery,
  exportOpen, format, exportScope, columns, addRow, removeRow, save, reload, query, cancel, copyRow, download, observation, stateText, rowResult } = useDeviceMac()
const page = ref(1)
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / 50)))
const visible = computed(() => rows.value.slice((Math.min(page.value, pages.value) - 1) * 50, Math.min(page.value, pages.value) * 50))
const fields = [['stationName', '站名'], ['ipAddress', 'IP地址'], ['deviceName', '设备名'], ['deviceModel', '设备型号'], ['serialNumber', '设备序列号']]
const counts = computed(() => {
  const results = task.value?.results || []
  const count = key => results.filter(item => item.status === key).length
  return `映射 ${count('MAPPING_FOUND')} · 缓存 ${count('CACHED_MAPPING')} · 超时 ${count('QUERY_TIMEOUT')} · 取消 ${count('QUERY_CANCELLED')} · 跳过 ${count('SKIPPED')} · 失败 ${results.filter(item => !['MAPPING_FOUND', 'CACHED_MAPPING', 'QUERY_TIMEOUT', 'QUERY_CANCELLED', 'SKIPPED'].includes(item.status)).length}`
})
function leave() { return !dirty.value || window.confirm('当前台账尚未保存。离开后本次应用内仍保留草稿，关闭或刷新浏览器将丢失，是否离开？') }
function unload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate((to, from) => to.path === from.path || leave())
onMounted(() => window.addEventListener('beforeunload', unload))
onUnmounted(() => window.removeEventListener('beforeunload', unload))
</script>

<template>
  <BasePanel title="设备台账" description="查询运行 Java 服务的本机所观察到的 IPv4 / MAC 映射；仅支持直接连接的局域网目标。缓存映射不能证明设备当前在线。">
    <template #actions><span class="badge">{{ !available ? '本机服务不可用' : capabilities?.supported ? 'Windows IPv4' : '当前系统不支持' }} · {{ dirty ? '未保存' : '已保存' }}</span></template>
    <div class="button-row">
      <button class="btn btn-primary" :disabled="busy || !loaded || rows.length >= (capabilities?.maxRows || 1000)" @click="addRow">新增行</button>
      <button class="btn btn-secondary" :disabled="busy || !loaded || !dirty || !available" @click="save">保存</button>
      <button class="btn btn-secondary" :disabled="busy" @click="reload">重新加载</button>
      <button class="btn btn-primary" :disabled="!canQuery || !rows.length" @click="query()">全部通信</button>
      <button v-if="running" class="btn btn-secondary" :disabled="pending || task?.status === 'CANCELLING'" @click="cancel">{{ task?.status === 'CANCELLING' ? '正在停止并清理…' : '停止查询' }}</button>
      <button class="btn btn-secondary" :disabled="busy || !loaded || !rows.length" @click="exportOpen = true">导出</button>
    </div>
    <p class="muted">查询只更新草稿，点击保存后写入项目数据目录。跨网段目标需要经过网关时无法通过本机 ARP 获取其真实 MAC；代理 ARP 返回的也可能是代理地址。</p>
    <p v-if="capabilities" class="muted">最多 {{ capabilities.maxRows }} 行 · {{ capabilities.concurrency }} 个目标并发 · 单目标总超时 {{ capabilities.timeoutSeconds }} 秒。{{ capabilities.message }}</p>
    <p v-if="error" class="mac-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="task" role="status" aria-live="polite">{{ task.status }} · {{ task.done }}/{{ task.total }} 行 · {{ counts }}<p v-if="task.errorCode" class="mac-error">{{ task.errorCode }} · {{ task.message }}</p></div>
    <div class="mac-table top-gap">
      <table><thead><tr><th>选择</th><th>序号</th><th v-for="field in fields" :key="field[0]">{{ field[1] }}</th><th>设备MAC地址 / 状态</th><th>安装位置</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in visible" :key="row.recordId">
          <td><input v-model="selected" type="checkbox" :value="row.recordId" :aria-label="`选择 ${row.ipAddress || '空白行'}`"></td><td>{{ rows.indexOf(row) + 1 }}</td>
          <td v-for="field in fields" :key="field[0]"><input v-model="row[field[0]]" class="input mac-input" :maxlength="256" :disabled="busy" :aria-label="field[1]" :placeholder="field[0] === 'ipAddress' ? '192.168.1.20' : ''"></td>
          <td class="mac-result"><strong>{{ observation(row)?.macAddress || '—' }}</strong><small :class="{ 'mac-error': rowResult(row) && !rowResult(row).observation }">{{ stateText(row) }}</small>
            <small v-if="observation(row)">{{ observation(row).source }} · {{ observation(row).neighborState }} · 接口 {{ observation(row).interfaceIndex }}<br>{{ new Date(observation(row).checkedAt).toLocaleString() }}（读取时间）</small>
            <small v-if="rowResult(row) && !rowResult(row).observation && observation(row)">上方为历史观察值，本次未成功</small>
            <small v-if="row.lastObservation && !observation(row)">历史 {{ row.lastObservation.ipAddress }}：{{ row.lastObservation.macAddress }}（与当前 IP 不同）</small>
          </td>
          <td><input v-model="row.location" class="input mac-input" maxlength="256" :disabled="busy" aria-label="安装位置"></td>
          <td><div class="mac-actions"><button class="btn btn-ghost" :disabled="!canQuery || !row.ipAddress.trim()" @click="query(row)">通信</button><button class="btn btn-ghost" :disabled="pending || !observation(row)" @click="copyRow(row, true)">复制MAC</button><button class="btn btn-ghost" :disabled="pending" @click="copyRow(row)">复制整行</button><button class="btn btn-ghost" :disabled="busy" @click="removeRow(row)">删除</button></div></td>
        </tr><tr v-if="!rows.length"><td colspan="10" class="muted">{{ loaded ? '暂无台账，请新增行并填写设备 IP。' : '正在加载，请确认 Java 本机服务已启动。' }}</td></tr></tbody>
      </table>
    </div>
    <div class="button-row top-gap"><span class="muted">共 {{ rows.length }} 行 · 已选 {{ selected.length }} 行 · 第 {{ Math.min(page, pages) }}/{{ pages }} 页</span><button class="btn btn-ghost" :disabled="page <= 1" @click="page--">上一页</button><button class="btn btn-ghost" :disabled="page >= pages" @click="page++">下一页</button></div>
  </BasePanel>
  <div v-if="exportOpen" class="modal-backdrop"><section class="modal mac-modal" role="dialog" aria-modal="true" aria-labelledby="mac-export-title"><h2 id="mac-export-title">导出设备台账</h2>
    <div class="form-grid two"><label class="field"><span>文件格式</span><select v-model="format" class="input"><option value="xlsx">Excel .xlsx</option><option value="docx">Word .docx</option></select></label><label class="field"><span>导出范围</span><select v-model="exportScope" class="input"><option value="all">全部草稿行</option><option value="selected">已选行</option></select></label></div>
    <p>选择导出列（序号必选）</p><div class="mac-columns"><label v-for="column in macColumns" :key="column[0]"><input v-model="columns" type="checkbox" :value="column[0]" :disabled="column[0] === 'index'"> {{ column[1] }}</label></div>
    <p class="muted">导出当前草稿中的观察值与读取时间；历史观察不代表设备现在在线。</p><p v-if="error" class="mac-error">{{ error.message }}</p>
    <div class="button-row top-gap"><button class="btn btn-primary" :disabled="busy" @click="download">生成文件</button><button class="btn btn-secondary" :disabled="pending" @click="exportOpen = false">关闭</button></div>
  </section></div>
</template>

<style scoped>
.mac-table { overflow: auto; max-height: 62vh; }
.mac-table table { min-width: 1350px; }
.mac-input { min-width: 110px; padding: 7px; }
.mac-result { min-width: 235px; white-space: normal; overflow-wrap: anywhere; }
.mac-result small { display: block; margin-top: 5px; color: var(--muted); }
.mac-result strong { font-family: Consolas, monospace; }
.mac-actions { display: grid; grid-template-columns: repeat(2, auto); min-width: 145px; }
.mac-error, .mac-result small.mac-error { color: var(--danger); overflow-wrap: anywhere; }
.mac-modal { width: min(620px, 94vw); max-height: 90vh; overflow: auto; }
.mac-columns { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
button:disabled { opacity: .6; cursor: not-allowed; }
</style>
