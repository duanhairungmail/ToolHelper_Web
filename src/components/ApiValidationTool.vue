<script setup>
import { computed, ref, watch } from 'vue'
import BasePanel from './BasePanel.vue'
import LogDrawer from './LogDrawer.vue'
import { useLogDrawer } from '@/composables/useLogDrawer'
import { useApiValidation } from '@/composables/useApiValidation'

const { lines, append, clear, expanded } = useLogDrawer()
const { snapshot, endpoints, error, pending, available, tab, dialog, selectedIds, cron, nextRun, form, connected, running, busy, canRun,
  refresh, login, logout, openSelection, submitSelection, query, stop, cancel, preview, clearLogs, download } = useApiValidation({ append, clear })
const format = ref('xlsx'), page = ref(1), detail = ref(null)
const rows = computed(() => snapshot.value?.[tab.value] || [])
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / 50)))
const visible = computed(() => rows.value.slice((page.value - 1) * 50, page.value * 50))
watch(tab, () => { page.value = 1; detail.value = null })
watch(pages, total => { if (page.value > total) page.value = total })
const outcome = value => ({ BUSINESS_OK: '业务码成功', HTTP_OK: 'HTTP成功/业务未确认', BUSINESS_ERROR: '业务错误', HTTP_ERROR: 'HTTP错误', INVALID_RESPONSE: '响应格式异常', AUTH_REQUIRED: '登录/权限失效', SKIPPED: '未执行', TIMEOUT: '超时', NETWORK_ERROR: '网络/读取错误' })[value] || value
</script>

<template>
  <BasePanel title="连接与认证" description="Java 直连目标系统；一个本机服务同时使用一个验证目标。Token 过期后停止，需手动重新登录。">
    <template #actions><span class="badge" role="status">{{ !available ? '本机服务不可用' : connected ? 'Token 已获取' : '未登录目标' }}</span></template>
    <form @submit.prevent="login">
      <fieldset class="validation-fields" :disabled="busy || connected">
        <div class="form-grid">
          <label class="field"><span>协议</span><select v-model="form.scheme" class="input"><option value="https">HTTPS</option><option value="http">HTTP（可信网络）</option></select></label>
          <label class="field"><span>目标主机</span><input v-model="form.host" class="input" required maxlength="253" placeholder="IP 或 ASCII DNS 名" autocomplete="off"></label>
          <label class="field"><span>端口</span><input v-model.number="form.port" type="number" class="input" required min="1" max="65535" step="1"></label>
          <label class="field"><span>用户名</span><input v-model="form.username" class="input" required maxlength="256" autocomplete="off"></label>
          <label class="field"><span>密码</span><input v-model="form.password" type="password" class="input" :required="!connected" maxlength="2048" autocomplete="new-password"></label>
          <label class="field"><span>凭据模式</span><select v-model="form.credentialMode" class="input"><option value="AES_CBC">AES-256-CBC（旧协议兼容）</option><option value="PLAIN">明文凭据</option></select></label>
          <label class="field"><span>登录格式</span><select v-model="form.loginFormat" class="input"><option value="LEGACY_QUERY">旧版查询参数（userName/password）</option><option value="JSON">POST JSON（userName/password）</option></select></label>
        </div>
        <p v-if="form.scheme === 'http'" class="validation-warning"><label><input v-model="form.allowInsecureHttp" type="checkbox" required> 我确认仅在可信网络使用 HTTP；AES 固定密钥不能保护链路中的凭据或 Token。</label></p>
        <p class="muted">提交后立即清空密码，不保存用于续登。旧版查询格式可能被目标访问日志记录，不会失败后自动切换格式。</p>
        <button v-if="!connected" class="btn btn-primary" type="submit" :disabled="!available">登录目标</button>
      </fieldset>
    </form>
    <div class="button-row top-gap"><button v-if="connected" class="btn btn-secondary" :disabled="busy" @click="logout">更换连接 / 退出登录</button><button class="btn btn-ghost" :disabled="pending" @click="refresh">刷新状态</button><span v-if="snapshot?.target" class="muted">当前目标：{{ snapshot.target }}</span></div>
    <p v-if="error" class="validation-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
  </BasePanel>
  <BasePanel title="验证操作" description="默认选择13个只读GET；三个复位/写入POST需要单独选择并再次确认，自动检测禁止POST。">
    <div class="button-row">
      <button class="btn btn-primary" :disabled="!canRun" @click="openSelection('verify')">开始验证</button>
      <button class="btn btn-secondary" :disabled="!canRun" @click="query(false)">获取设备ID</button>
      <button class="btn btn-secondary" :disabled="!canRun" @click="query(true)">MQTT主题</button>
      <button v-if="snapshot?.auto?.running" class="btn btn-secondary" :disabled="pending" @click="stop">停止检测</button>
      <button v-else class="btn btn-secondary" :disabled="!canRun" @click="openSelection('auto')">自动检测</button>
      <button class="btn btn-ghost" title="只清日志，保留表格和任务" @click="clearLogs">清空结果（仅日志）</button>
      <select v-model="format" class="input export-format" aria-label="导出格式"><option value="xlsx">Excel .xlsx</option><option value="docx">Word .docx</option></select>
      <button class="btn btn-secondary" :disabled="busy || !rows.length || !snapshot?.connectionId" @click="download(format)">导出当前表格</button>
    </div>
    <p v-if="!connected" class="muted">请先登录目标系统。设备ID与主题会读取全部分页，失败时不将部分数据标成全量。</p>
    <div v-if="snapshot?.busy" class="top-gap" role="status" aria-live="polite">{{ snapshot.currentTask?.operation }} · {{ snapshot.currentTask?.status }} · 已完成 {{ snapshot.currentTask?.done }}<template v-if="snapshot.currentTask?.total"> / {{ snapshot.currentTask.total }}</template><template v-if="snapshot.currentTask?.page"> · 第{{ snapshot.currentTask.page }}页</template><button v-if="!snapshot.auto?.running" class="btn btn-ghost" :disabled="pending" @click="cancel">取消当前任务</button></div>
    <p v-if="snapshot?.auto" class="muted">自动检测：{{ snapshot.auto.running ? '运行中（离开页面继续，后端重启停止）' : '已停止' }} · 轮次 {{ snapshot.auto.cycles }} · 最近10轮通过率 {{ snapshot.auto.successRate == null ? '—' : snapshot.auto.successRate.toFixed(1) + '%' }}<template v-if="snapshot.auto.nextRun"><br>下次：{{ snapshot.auto.nextRun }}（{{ snapshot.auto.zone }}）</template><template v-if="snapshot.auto.stopReason"><br>{{ snapshot.auto.stopReason }}</template></p>
    <p v-if="snapshot?.currentTask?.status === 'FAILED'" class="validation-error">{{ snapshot.currentTask.message }} · {{ snapshot.currentTask.errorCode }}</p>
  </BasePanel>
  <BasePanel title="结果详情" description="HTTP可达不代表业务成功。无可用设备ID时跳过依赖项，不回退到设备1。">
    <div class="button-row" role="tablist"><button v-for="item in [{ id: 'results', title: '验证结果' }, { id: 'devices', title: '设备列表' }, { id: 'topics', title: 'MQTT主题' }]" :key="item.id" class="btn" :class="tab === item.id ? 'btn-primary' : 'btn-secondary'" role="tab" :aria-selected="tab === item.id" @click="tab = item.id">{{ item.title }}（{{ snapshot?.[item.id]?.length || 0 }}）</button></div>
    <div class="validation-table top-gap">
      <table v-if="tab === 'results'"><thead><tr><th>序号</th><th>接口</th><th>方法</th><th>HTTP</th><th>耗时</th><th>可达</th><th>结果</th><th>详情</th></tr></thead><tbody><tr v-for="row in visible" :key="row.endpointId"><td>{{ row.index }}</td><td>{{ row.name }}</td><td>{{ row.method }}</td><td>{{ row.httpStatus ?? '—' }}</td><td>{{ row.elapsedMs }} ms</td><td>{{ row.reachable ? '是' : '未确认' }}</td><td>{{ outcome(row.outcome) }}</td><td><button class="btn btn-ghost" @click="detail = row">查看</button></td></tr></tbody></table>
      <table v-else-if="tab === 'devices'"><thead><tr><th>设备名称</th><th>通信地址</th><th>设备ID</th></tr></thead><tbody><tr v-for="(row, index) in visible" :key="index"><td>{{ row.name || '无' }}</td><td>{{ row.address || '无' }}</td><td>{{ row.id || '无' }}</td></tr></tbody></table>
      <table v-else><thead><tr><th>网关名称</th><th>下行主题（设备订阅）</th><th>上行主题（设备发布）</th></tr></thead><tbody><tr v-for="(row, index) in visible" :key="index"><td>{{ row.gateName || '无' }}</td><td>{{ row.downTopic || '无' }}</td><td>{{ row.upTopic || '无' }}</td></tr></tbody></table>
      <p v-if="!rows.length" class="muted">{{ running ? '正在处理，完成后的数据将在这里显示。' : '暂无结果，请登录后执行对应操作。' }}</p>
    </div>
    <div class="button-row top-gap"><span class="muted">共{{ rows.length }}条 · 第{{ page }}/{{ pages }}页（每页50条；导出含当前表格全部记录）</span><button class="btn btn-ghost" :disabled="page <= 1" @click="page--">上一页</button><button class="btn btn-ghost" :disabled="page >= pages" @click="page++">下一页</button></div>
    <div v-if="detail" class="validation-detail"><strong>{{ detail.name }}</strong><p>{{ detail.method }} {{ detail.path }}</p><p>{{ detail.message }}</p><button class="btn btn-ghost" @click="detail = null">关闭详情</button></div>
  </BasePanel>
  <LogDrawer v-model:expanded="expanded" :lines="lines" @clear="clearLogs" />
  <div v-if="dialog" class="modal-backdrop"><section class="modal validation-modal" role="dialog" aria-modal="true" aria-labelledby="validation-title"><h2 id="validation-title">{{ dialog === 'auto' ? '自动检测配置' : '选择验证接口' }}</h2><div class="button-row"><button class="btn btn-ghost" @click="selectedIds = endpoints.filter(item => !item.sideEffect).map(item => item.id)">选择13个GET</button><button class="btn btn-ghost" @click="selectedIds = []">全不选</button><span>已选{{ selectedIds.length }}/16</span></div><div class="endpoint-list"><label v-for="item in endpoints" :key="item.id"><input v-model="selectedIds" type="checkbox" :value="item.id" :disabled="dialog === 'auto' && item.sideEffect"> {{ item.name }} · {{ item.method }} <strong v-if="item.sideEffect" class="validation-warning">可能影响真实设备</strong></label></div><template v-if="dialog === 'auto'"><label class="field"><span>Cron（5/6字段，拒绝年份）</span><input v-model="cron" class="input" maxlength="128"></label><div class="button-row top-gap"><button class="btn btn-ghost" @click="cron = '0 * * * * ?'">每分钟</button><button class="btn btn-ghost" @click="cron = '0 */5 * * * ?'">每5分钟</button><button class="btn btn-secondary" :disabled="pending" @click="preview">校验并预览</button></div><p v-if="nextRun">下次：{{ nextRun }}</p></template><p v-if="error" class="validation-error">{{ error.message }}</p><div class="button-row top-gap"><button class="btn btn-primary" :disabled="!canRun || !selectedIds.length" @click="submitSelection">{{ dialog === 'auto' ? '启动自动检测' : '验证所选接口' }}</button><button class="btn btn-secondary" @click="dialog = ''">取消</button></div></section></div>
</template>

<style scoped>
.validation-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.validation-error { color: var(--danger); overflow-wrap: anywhere; }
.validation-warning { color: #92400e; }
.export-format { width: auto; }
.validation-table { overflow: auto; max-height: 460px; }
.validation-table td { max-width: 420px; white-space: normal; overflow-wrap: anywhere; }
.validation-modal { width: min(680px, 94vw); max-height: 90vh; overflow: auto; }
.endpoint-list { max-height: 36vh; overflow: auto; margin: 12px 0; display: grid; gap: 8px; }
.validation-detail { margin-top: 12px; overflow-wrap: anywhere; padding: 12px; border: 1px solid var(--border); }
button:disabled, fieldset:disabled { opacity: .6; }
button:disabled { cursor: not-allowed; }
</style>
