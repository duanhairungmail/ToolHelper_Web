<script setup>
import { computed, ref } from 'vue'
import BasePanel from './BasePanel.vue'
import { useDruidScan, druidColumns, druidLabel as label } from '../composables/useDruidScan.js'

const { mode, target, batch, username, password, capabilities, history, active, task, viewId, rows, detail, selected, error, notice,
  pending, available, running, busy, exportOpen, format, scope, includeDetails, columns, refresh, choose, start, cancel, clear, showDetail, download, copySuggestions } = useDruidScan()
const credentialsOpen = ref(false), page = ref(1)
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / 25)))
const visibleRows = computed(() => rows.value.slice((Math.min(page.value, pages.value) - 1) * 25, Math.min(page.value, pages.value) * 25))
</script>

<template>
  <BasePanel title="Druid 历史未授权漏洞复测" description="复测监控首页及 SQL、Session 等端点，区分仍可匿名访问、本次匿名访问已拦截、无法判断。页面暴露与数据暴露分别记录。">
    <template #actions><span class="badge">{{ available ? '直连 · TLS 校验开启' : '本机服务不可用' }}</span></template>
    <div class="form-grid two">
      <label class="field"><span>目标模式</span><select v-model="mode" class="input" :disabled="busy"><option value="single">单目标</option><option value="batch">批量 URL（每行一个）</option></select></label>
      <label v-if="mode === 'single'" class="field"><span>完整目标 URL</span><input v-model="target" class="input" maxlength="2048" :disabled="busy" placeholder="http://目标:端口/应用路径/druid/index.html"></label>
    </div>
    <label v-if="mode === 'batch'" class="field top-gap"><span>目标 URL 列表</span><textarea v-model="batch" class="input textarea" :disabled="busy" maxlength="205000" placeholder="每行填写一个完整 http/https URL；不展开网段、端口范围或猜测目录"></textarea></label>
    <p class="muted">输入监控根或 index.html；应用上下文会保留。检测以 Java 服务所在电脑为来源，不沿用插件下载代理。</p>
    <div class="button-row"><button class="btn btn-primary" :disabled="busy || !available" @click="start(false)">开始检测</button>
      <button v-if="running" class="btn btn-secondary" :disabled="pending || active.status === 'CANCELLING'" @click="cancel">{{ active.status === 'CANCELLING' ? '正在停止并清理…' : '停止检测' }}</button>
      <button class="btn btn-secondary" :disabled="busy || !available || !history.length" @click="clear">清空结果</button>
      <button class="btn btn-secondary" :disabled="busy || !rows.length" @click="exportOpen = true">导出报告</button>
      <button class="btn btn-secondary" :disabled="pending" @click="refresh">刷新状态</button>
      <label><input v-model="credentialsOpen" type="checkbox" :disabled="busy || mode !== 'single'" @change="username = ''; password = ''"> 单组凭据验证（默认关闭）</label>
    </div>
    <section v-if="credentialsOpen && mode === 'single'" class="credential-box top-gap">
      <p>独立执行匿名基线，再用指定的一组凭据验证已识别的登录机制；无字典、不自动重试。它与业务系统登录不一定共用账号。</p>
      <div class="form-grid two"><label class="field"><span>Druid 用户名</span><input v-model="username" class="input" maxlength="256" :disabled="busy" autocomplete="off"></label><label class="field"><span>Druid 密码</span><input v-model="password" class="input" type="password" maxlength="2048" :disabled="busy" autocomplete="new-password"></label></div>
      <button class="btn btn-secondary top-gap" :disabled="busy || !available" @click="start(true)">验证指定凭据</button><p class="muted">密码发起后清空，不保存、不写入报告；HTTP 传输会单独确认。</p>
    </section>
    <p v-if="capabilities" class="muted">{{ capabilities.ruleVersion }} · 最多 {{ capabilities.limits.maxTargets }} 目标 · {{ capabilities.limits.concurrency }} 并发/同源串行 · 请求 {{ capabilities.limits.requestSeconds }} 秒/目标 {{ capabilities.limits.targetSeconds }} 秒 · 每目标最多 {{ capabilities.limits.maxRequests }} 次请求。结果仅内存保留 30 分钟，服务重启清空。</p>
    <p v-if="error" class="druid-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="active" role="status" aria-live="polite">{{ label(active.status) }} · {{ active.done }}/{{ active.total }} 个目标；停止时会等待实际请求清理。</p>
  </BasePanel>
  <BasePanel title="检测结果" class="top-gap">
    <label class="field"><span>内存任务记录</span><select :value="viewId" class="input" :disabled="pending" @change="choose($event.target.value)"><option value="">暂无记录</option><option v-for="item in history" :key="item.taskId" :value="item.taskId">{{ new Date(item.createdAt).toLocaleString() }} · {{ item.kind === 'CREDENTIAL' ? '单组凭据' : '匿名检测' }} · {{ label(item.status) }} · {{ item.done }}/{{ item.total }}</option></select></label>
    <p v-if="task" class="muted">{{ label(task.status) }} · {{ task.done }}/{{ task.total }} 个目标 · {{ task.ruleVersion }}<template v-if="task.errorCode"> · {{ task.errorCode }}</template>。处理结束不表示所有目标无风险；风险为本项目规则等级。</p>
    <div class="druid-table"><table><thead><tr><th>选择</th><th>目标</th><th>历史漏洞复测结论</th><th>识别 / 访问状态</th><th>风险</th><th>执行 / 规则</th><th>凭据验证</th><th>耗时</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in visibleRows" :key="row.targetId"><td><input v-model="selected" type="checkbox" :value="row.targetId" :aria-label="`选择 ${row.target}`"></td><td class="druid-target">{{ row.target }}</td><td :class="{ 'druid-error': ['DATA_EXPOSED', 'PAGE_EXPOSED'].includes(row.retest?.status) }">{{ row.retest?.conclusion || '请重新检测以获取复测结论' }}</td><td>{{ label(row.identification) }}<br>{{ label(row.accessStatus) }}</td><td :class="{ 'druid-error': row.severity === 'HIGH' }">{{ label(row.severity) }}</td><td>{{ label(row.executionStatus) }}<br>{{ row.completedRules }}/{{ row.totalRules }}</td><td>{{ label(row.credentialStatus) }}</td><td>{{ row.elapsedMs }}ms</td><td><button class="btn btn-ghost" @click="showDetail(row)">查看详情</button></td></tr>
      <tr v-if="!rows.length"><td colspan="9" class="muted">尚无已完成目标。服务重启或结果过期后不会自动重发检测。</td></tr>
    </tbody></table></div>
    <div class="button-row top-gap"><span class="muted">共 {{ rows.length }} 项 · 已选 {{ selected.length }} 项 · 第 {{ Math.min(page, pages) }}/{{ pages }} 页</span><button class="btn btn-ghost" :disabled="page <= 1" @click="page--">上一页</button><button class="btn btn-ghost" :disabled="page >= pages" @click="page++">下一页</button></div>
  </BasePanel>
  <BasePanel v-if="detail" title="端点详情" class="top-gap"><p class="druid-target">{{ detail.target }}</p><p><strong>{{ detail.retest?.conclusion }}</strong></p><p class="muted">{{ detail.message }} · {{ new Date(detail.checkedAt).toLocaleString() }}</p>
    <div class="druid-table"><table><thead><tr><th>方法 / 路径</th><th>HTTP</th><th>复测结论 / 响应分类</th><th>样本 / 字段类别</th><th>证据说明</th></tr></thead><tbody><tr v-for="e in detail.evidence" :key="e.ruleId"><td>{{ e.method }} {{ e.path }}</td><td>{{ e.httpStatus || '—' }}</td><td>{{ e.retest?.conclusion }}<br>{{ label(e.status) }} · {{ label(e.severity) }}</td><td>{{ e.sampleCount }} 条样本<br>{{ e.fields.join(', ') || '—' }}</td><td>{{ e.message }}</td></tr></tbody></table></div>
  </BasePanel>
  <BasePanel title="整改建议与检测边界" class="top-gap"><template #actions><button class="btn btn-ghost" :disabled="!capabilities" @click="copySuggestions">复制建议</button></template><p v-for="item in capabilities?.remediation || []" :key="item">{{ item }}</p><p class="muted">不采集原始 SQL、Session、Cookie 或数据库凭据，不调用统计重置，不自动修改目标。真实生效配置需结合目标版本和代理拓扑核对。</p></BasePanel>
  <div v-if="exportOpen" class="modal-backdrop"><section class="modal druid-modal" role="dialog" aria-modal="true" aria-labelledby="druid-export-title"><h2 id="druid-export-title">导出 Druid 检测报告</h2>
    <div class="form-grid two"><label class="field"><span>文件格式</span><select v-model="format" class="input"><option value="xlsx">Excel .xlsx</option><option value="docx">Word .docx</option></select></label><label class="field"><span>导出范围</span><select v-model="scope" class="input"><option value="all">当前任务全部目标</option><option value="selected">已选目标</option></select></label></div>
    <p>选择导出列（序号必选）</p><div class="druid-columns"><label v-for="column in druidColumns" :key="column[0]"><input v-model="columns" type="checkbox" :value="column[0]" :disabled="column[0] === 'index'"> {{ column[1] }}</label></div>
    <p><label><input v-model="includeDetails" type="checkbox"> 附脱敏端点详情（Excel 独立工作表 / Word 详情章节）</label></p><p class="muted">仅导出已完成快照与证据类别；未知或未完成检查不会写成安全。</p><p v-if="error" class="druid-error">{{ error.message }}</p>
    <div class="button-row"><button class="btn btn-primary" :disabled="busy" @click="download">生成文件</button><button class="btn btn-secondary" :disabled="pending" @click="exportOpen = false">关闭</button></div>
  </section></div>
</template>

<style scoped>
.druid-table { overflow: auto; max-height: 58vh; margin-top: 16px; }
.druid-table table { min-width: 1020px; }
.druid-target { max-width: 300px; overflow-wrap: anywhere; }
.druid-error { color: var(--danger); overflow-wrap: anywhere; }
.credential-box { border: 1px solid var(--border); border-radius: 8px; padding: 14px; }
.druid-modal { width: min(680px, 94vw); max-height: 90vh; overflow: auto; }
.druid-columns { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
button:disabled { opacity: .6; cursor: not-allowed; }
</style>
