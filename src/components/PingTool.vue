<script setup>
import { computed, ref, watch } from 'vue'
import BasePanel from './BasePanel.vue'
import { pingResultLabels, pingStatusLabels, usePing } from '../composables/usePing.js'

const { text, fileBase64, capabilities, parsed, task, rows, pending, available, running, busy, error, notice,
  concurrency, count, timeoutMs, sortBy, format, parseTargets, clearParsed, start, stop, clearResults, download, selectFile, refresh } = usePing()

const page = ref(1)
const pageSize = ref(5)
const pageCount = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
const currentPage = computed(() => Math.min(page.value, pageCount.value))
const pageRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})
watch([sortBy, pageSize], () => { page.value = 1 })
watch(() => task.value?.taskId, () => { page.value = 1 })
watch(pageCount, value => { if (page.value > value) page.value = value })

function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function loss(value) { return value == null ? '—' : value.toFixed(1) + '%' }
function rtt(row) { return row.averageRttMs == null ? '—' : row.rttBelowOneMs ? '<1 ms' : row.averageRttMs.toFixed(2) + ' ms' }
function resultClass(status) { return ({ RESPONSIVE: 'success', PARTIAL_LOSS: 'warn', NO_REPLY: 'muted', ERROR: 'error', CANCELLED: 'muted' })[status] || 'muted' }
function showError(row) { return row.errorCode ? row.errorCode + ' · ' + row.message : row.message }
</script>

<template>
  <div class="ping-page">
    <div class="ping-top">
      <BasePanel title="目标与探测参数" description="解析与导入只做格式校验；只有点击“开始 Ping”后，才从本机发送 ICMP 探测。">
        <template #actions>
          <span class="badge" :class="available && capabilities?.available ? '' : 'warn'" role="status">
            {{ !available ? '正在连接本机服务' : capabilities?.available ? '本机 · ' + capabilities.platform : '系统 Ping 工具不可用' }}
          </span>
        </template>
        <div class="ping-input-grid">
          <div>
            <label class="field"><span>目标列表</span><textarea v-model="text" class="input textarea ping-targets" :disabled="busy" placeholder="每行一个目标：&#10;192.168.1.10&#10;192.168.1.1/30&#10;192.168.1.20-30&#10;# 以 # 开头的行作为注释" @input="clearParsed"></textarea></label>
            <div class="button-row top-gap">
              <label class="btn btn-secondary ping-file">导入 UTF-8 TXT<input type="file" accept=".txt,text/plain" :disabled="busy" @change="selectFile($event.target.files?.[0]); $event.target.value = ''"></label>
              <button class="btn btn-secondary" :disabled="busy || !available" @click="parseTargets">解析目标</button>
              <span v-if="parsed" class="muted">已解析 {{ parsed.total }} 个目标</span>
              <span v-else-if="fileBase64" class="muted">已载入 TXT 文件</span>
            </div>
          </div>
          <div class="ping-parameters">
            <label class="field"><span>并发目标数（1–{{ capabilities?.limits?.maxConcurrency || 200 }}）</span><input v-model.number="concurrency" class="input" type="number" min="1" :max="capabilities?.limits?.maxConcurrency || 200" :disabled="busy"></label>
            <label class="field"><span>每目标次数（1–10）</span><input v-model.number="count" class="input" type="number" min="1" max="10" :disabled="busy"></label>
            <label class="field"><span>单次超时（100–10000 ms）</span><input v-model.number="timeoutMs" class="input" type="number" min="100" max="10000" step="100" :disabled="busy"></label>
            <p class="muted">最多 {{ capabilities?.limits?.maxTargets || 4096 }} 个 IPv4 目标；超过上限会整体拒绝，不会截断。</p>
          </div>
        </div>
        <div class="button-row top-gap ping-run-actions">
          <button class="btn btn-primary" :disabled="busy || !available || !capabilities?.available || !parsed?.targets?.length" @click="start">开始 Ping</button>
          <button v-if="running" class="btn btn-secondary" :disabled="pending || task?.status === 'CANCELLING'" @click="stop">停止</button>
          <button class="btn btn-ghost" :disabled="pending" @click="refresh">刷新状态</button>
          <span v-if="task" class="muted ping-progress">{{ pingStatusLabels[task.status] || task.status }} · {{ task.summary.done }}/{{ task.total }} 完成</span>
        </div>
        <p v-if="notice" class="ping-notice" role="status">{{ notice }}</p>
        <p v-if="error" class="validation-error" role="alert">{{ error.message }} <small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
        <div v-if="parsed?.targets?.length" class="ping-target-preview"><span>本次目标预览</span><code>{{ parsed.targets.slice(0, 20).join(' · ') }}<template v-if="parsed.total > 20"> · …（共 {{ parsed.total }} 个）</template></code></div>
      </BasePanel>

      <div class="ping-stats">
        <div class="metric"><span>目标总数</span><strong>{{ task?.total || 0 }}</strong></div>
        <div class="metric"><span>已完成</span><strong>{{ task?.summary?.done || 0 }}</strong></div>
        <div class="metric"><span>运行中</span><strong>{{ task?.summary?.running || 0 }}</strong></div>
        <div class="metric"><span>有响应</span><strong>{{ task?.summary?.responsive || 0 }}</strong></div>
        <div class="metric"><span>部分丢包</span><strong>{{ task?.summary?.partialLoss || 0 }}</strong></div>
        <div class="metric"><span>无响应</span><strong>{{ task?.summary?.noReply || 0 }}</strong></div>
        <div class="metric"><span>探测错误</span><strong>{{ task?.summary?.errors || 0 }}</strong></div>
        <div class="metric"><span>停止未执行</span><strong>{{ task?.summary?.cancelled || 0 }}</strong></div>
        <div class="metric ping-average"><span>成功目标平均 RTT 的平均值</span><strong>{{ task?.summary?.averageRttMs == null ? '—' : task.summary.averageRttMs.toFixed(2) + ' ms' }}</strong></div>
      </div>
    </div>

    <BasePanel title="检测结果" class="ping-results">
      <template #actions>
        <div class="button-row ping-result-actions">
          <label class="ping-page-size"><span class="muted">每页</span><select v-model.number="pageSize" class="input" aria-label="每页显示条数"><option :value="5">5 条</option><option :value="10">10 条</option><option :value="20">20 条</option><option :value="50">50 条</option></select></label>
          <label class="ping-sort"><span class="muted">排序</span><select v-model="sortBy" class="input"><option value="input">输入顺序</option><option value="ip">IP 地址</option><option value="status">状态</option><option value="rtt">平均 RTT</option></select></label>
          <select v-model="format" class="input ping-format" aria-label="导出格式"><option value="xlsx">XLSX</option><option value="csv">CSV</option></select>
          <button class="btn btn-ghost" :disabled="busy || !task?.summary?.done" @click="download">导出结果</button>
          <button class="btn btn-ghost" :disabled="busy || !task" @click="clearResults">清空当前结果</button>
        </div>
      </template>
      <p v-if="task" class="muted ping-result-note">“无响应”只表示本次未收到 ICMP Echo 回复，不代表主机离线；错误目标与无响应分开统计。取消任务可导出已经完成的行。</p>
      <div class="ping-table-wrap">
        <table>
          <thead><tr><th>目标 IP</th><th>结论</th><th>成功 / 探测</th><th>丢包率</th><th>平均 RTT</th><th>完成时间</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="row in pageRows" :key="row.ip">
              <td><code>{{ row.ip }}</code></td><td><span class="ping-state" :class="resultClass(row.status)">{{ pingResultLabels[row.status] || row.status }}</span></td>
              <td>{{ row.received == null ? '—' : row.received + ' / ' + row.sent }}</td><td>{{ loss(row.lossPercent) }}</td><td>{{ rtt(row) }}</td><td>{{ time(row.completedAt) }}</td><td class="ping-message">{{ showError(row) || '—' }}</td>
            </tr>
            <tr v-if="!rows.length"><td colspan="7"><div class="empty-state">{{ running ? '等待首批目标完成…' : task ? '任务尚无已完成结果' : '解析并确认目标后，开始 Ping 以查看结果' }}</div></td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="rows.length" class="button-row ping-pagination" aria-label="检测结果分页">
        <span class="muted">共 {{ rows.length }} 条 · 第 {{ currentPage }}/{{ pageCount }} 页</span>
        <button class="btn btn-ghost" :disabled="currentPage <= 1" @click="page = currentPage - 1">上一页</button>
        <button class="btn btn-ghost" :disabled="currentPage >= pageCount" @click="page = currentPage + 1">下一页</button>
      </div>
    </BasePanel>
  </div>
</template>

<style scoped>
.ping-page { display: flex; min-height: 100%; flex-direction: column; gap: 16px; }
.ping-top { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(220px, 1fr); gap: 16px; align-items: stretch; }
.ping-top > :deep(.panel), .ping-results { min-width: 0; }
.ping-top > :deep(.panel) { display: flex; flex-direction: column; }
.ping-input-grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(230px, .7fr); gap: 20px; }
.ping-targets { min-height: 150px; resize: vertical; font: 12px/1.55 var(--font-mono); }
.ping-parameters { display: grid; align-content: start; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; }
.ping-parameters p { grid-column: 1 / -1; margin: 0; }
.ping-file { position: relative; overflow: hidden; }
.ping-file input { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; }
.ping-run-actions { align-items: center; }
.ping-progress { margin-left: auto; }
.ping-notice { margin: 10px 0 0; color: var(--muted); }
.ping-target-preview { display: grid; gap: 5px; margin-top: 12px; padding: 9px 11px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg); }
.ping-target-preview span { color: var(--muted); font-size: 11px; }
.ping-target-preview code { overflow-wrap: anywhere; font: 11px/1.6 var(--font-mono); }
.ping-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.ping-stats .metric { min-height: 76px; padding: 11px 13px; }
.ping-stats .metric strong { font-size: 18px; }
.ping-average { grid-column: 1 / -1; }
.ping-results { display: flex; min-height: 0; flex: 1; flex-direction: column; }
.ping-results :deep(.panel-head) { align-items: flex-start; flex-wrap: wrap; }
.ping-result-actions { justify-content: flex-end; }
.ping-sort, .ping-page-size { display: flex; align-items: center; gap: 7px; }
.ping-sort select, .ping-page-size select, .ping-format { width: auto; min-height: 40px; }
.ping-result-note { margin: -5px 0 12px; font-size: 12px; }
.ping-table-wrap { min-height: 0; flex: 1; overflow-x: auto; }
.ping-table-wrap th { white-space: nowrap; }
.ping-table-wrap td { vertical-align: top; }
.ping-table-wrap code { font: 12px var(--font-mono); }
.ping-state { display: inline-flex; padding: 3px 8px; border-radius: 999px; background: var(--border-soft); white-space: nowrap; }
.ping-state.success { color: var(--success); background: color-mix(in oklch,var(--success) 12%,var(--surface)); }
.ping-state.warn { color: var(--warn); background: color-mix(in oklch,var(--warn) 16%,var(--surface)); }
.ping-state.error { color: var(--danger); background: color-mix(in oklch,var(--danger) 12%,var(--surface)); }
.ping-message { min-width: 220px; max-width: 420px; overflow-wrap: anywhere; }
.ping-pagination { justify-content: flex-end; align-items: center; margin-top: 12px; }
.ping-pagination > span { margin-right: auto; }
@media (max-width: 1100px) { .ping-top { grid-template-columns: 1fr; } .ping-stats { grid-template-columns: repeat(4, minmax(0, 1fr)); } .ping-average { grid-column: span 2; } }
@media (max-width: 700px) { .ping-input-grid, .ping-parameters { grid-template-columns: 1fr; } .ping-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ping-average { grid-column: 1 / -1; } .ping-result-actions { justify-content: flex-start; } .ping-results { min-height: 420px; } }
</style>
