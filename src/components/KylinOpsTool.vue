<script setup>
import { computed, nextTick, ref, watch, watchEffect } from 'vue'
import BasePanel from './BasePanel.vue'
import { useAppStore } from '../stores/app.js'
import { useKylinOps, kylinLabel as label } from '../composables/useKylinOps.js'
const { caps, connection, key, feature, action, host, port, username, password, sudoPassword, vncPassword, params,
  scan, plan, task, backups, error, notice, confirmed, running, busy, currentFeature, refresh, probe, trust,
  connect, disconnect, inspect, beginAction, approve, closePlan, cancel, sudoRemembered, clearSudo } = useKylinOps()
const store = useAppStore()
const confirmationDialog = ref(null)
const actionText = value => ({ DEPLOY: '部署', UNINSTALL: feature.value === 'optimization' ? '恢复优化' : '卸载',
  START: '启动', STOP: '停止', CLEAN_NOW: '立即清理', RELOAD: '重新加载配置', APPLY: '优化选中', REPAIR: '修复' }[value] || label(value))
const directActions = computed(() => (currentFeature.value?.actions || []).filter(item => item !== 'RESTORE'))
const hasBackup = computed(() => backups.value.some(item => item.feature === feature.value))
watch(plan, async value => {
  await nextTick()
  if (value && plan.value === value && !confirmationDialog.value?.open) confirmationDialog.value?.showModal()
})
watchEffect(() => {
  if (error.value) store.setStatus(`KylinOS：${error.value.message}`, 'error')
  else if (running.value) store.setStatus(`KylinOS：${label(task.value.status)} · ${label(task.value.step)}`)
  else if (connection.value?.connected) store.setStatus(`KylinOS：SSH已连接 ${connection.value.host}`, 'success')
  else store.setStatus('KylinOS：SSH未连接')
})
</script>

<template>
  <BasePanel title="SSH连接与目标" description="Java本机服务直接连接目标。先核对主机指纹，再认证；连接后不会自动部署或重启。">
    <template #actions><button class="btn btn-secondary" :disabled="busy" @click="refresh">刷新真实状态</button></template>
    <div v-if="!connection" class="form-grid four">
      <label class="field"><span>主机IP或DNS</span><input v-model="host" class="input" :disabled="busy" maxlength="253"></label>
      <label class="field"><span>SSH端口</span><input v-model.number="port" type="number" class="input" :disabled="busy" min="1" max="65535"></label>
      <label class="field"><span>SSH用户名</span><input v-model="username" class="input" :disabled="busy" maxlength="64" autocomplete="off"></label>
      <label class="field"><span>SSH密码</span><input v-model="password" class="input" type="password" :disabled="busy" autocomplete="new-password" maxlength="2048"></label>
    </div>
    <div v-if="!connection" class="button-row top-gap"><button class="btn btn-secondary" :disabled="busy || !caps" @click="probe">探测主机指纹</button><button class="btn btn-secondary" :disabled="busy || !key || key.trusted" @click="trust">核对并信任指纹</button><button class="btn btn-primary" :disabled="busy || !key?.trusted || !password" @click="connect">连接SSH</button></div>
    <p v-if="key" class="kylin-wrap">{{ key.host }}:{{ key.port }} · {{ key.algorithm }} · {{ key.fingerprint }} · {{ key.trusted ? '已信任' : '待核对' }}</p>
    <template v-if="connection"><p class="kylin-wrap"><strong>{{ connection.username }}@{{ connection.host }}:{{ connection.port }}</strong> · {{ connection.connected ? '已连接' : '已断开' }}</p><p class="muted">{{ connection.system.os }} {{ connection.system.version }} · {{ connection.system.arch }} · {{ connection.system.display }} · 目标时区 {{ connection.system.timezone }} · UID {{ connection.system.uid }}</p><button class="btn btn-secondary" :disabled="busy" @click="disconnect">断开连接</button></template>
    <p v-if="error" class="kylin-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p class="muted">SSH密码提交后清空；sudo密码验证成功后仅在当前页面与连接内临时记住。连接空闲15分钟断开。首次指纹需通过可信渠道核对，密钥变化会拒绝连接。</p>
  </BasePanel>
  <BasePanel title="运维策略" class="top-gap">
    <div class="kylin-tabs" role="group" aria-label="选择运维功能"><button v-for="item in caps?.features || []" :key="item.id" class="btn" :class="feature === item.id ? 'btn-primary' : 'btn-secondary'" :aria-pressed="feature === item.id" :disabled="busy || !!plan" @click="feature = item.id">{{ item.name }}</button></div>
    <h3>{{ currentFeature?.name }}</h3>
    <p>{{ currentFeature?.description }}</p>
    <p v-if="feature === 'activation'" class="muted">仅检测激活状态；未校准密钥激活命令，不收集激活密钥。</p>
    <template v-if="feature === 'patch'"><p class="kylin-wrap">修复补丁：{{ caps?.patch?.file }}<br>目标版本 {{ caps?.patch?.version }} · {{ caps?.patch?.architecture }}</p><p class="muted">点击修复后，确认上传并安装提供的补丁。安装脚本会启用并重启离线升级服务；验证检查包版本、配置及服务状态。结果基于此补丁版本，不代表其他漏洞均已修复。</p></template>
    <div v-if="feature === 'reboot'" class="form-grid two top-gap"><label class="field"><span>桌面登录用户（默认带自动登录）</span><input v-model="params.desktopUser" class="input" :disabled="busy" maxlength="32"></label><label class="field"><span>目标时区cron计划（五段数字或*）</span><input v-model="params.schedule" class="input" :disabled="busy"></label></div>
    <p v-if="feature === 'reboot'" class="muted">部署不立即重启。计划触发时临时开启LightDM自动登录，新一次启动后等待指定用户X11会话、最多60秒即移除配置；清理失败可能残留自动登录，需要现场核对。</p>
    <div v-if="feature === 'logs'" class="form-grid three top-gap"><label class="field"><span>journal保留天数</span><input v-model.number="params.journalDays" type="number" class="input" min="7" max="3650" :disabled="busy"></label><label class="field"><span>文件保留天数</span><input v-model.number="params.fileDays" type="number" class="input" min="30" max="3650" :disabled="busy"></label><label class="field"><span>cron计划</span><input v-model="params.schedule" class="input" :disabled="busy"></label></div>
    <div v-if="['vnc', 'opengauss'].includes(feature)" class="form-grid two top-gap"><label class="field"><span>{{ feature === 'vnc' ? '监听IPv4（默认回环，LAN需明确填写）' : 'openGauss监听IPv4' }}</span><input v-model="params.listenAddress" class="input" :disabled="busy"></label><label v-if="feature === 'vnc'" class="field"><span>VNC端口</span><input v-model.number="params.port" class="input" type="number" min="1024" max="65535" :disabled="busy"></label><label v-else class="field"><span>授权来源CIDR</span><input v-model="params.allowedCidr" class="input" placeholder="192.168.31.0/24" :disabled="busy"></label></div>
    <div v-if="feature === 'optimization'" class="kylin-table"><table><thead><tr><th>选择</th><th>优化项</th><th>能力</th><th>影响</th></tr></thead><tbody><tr v-for="item in caps?.optimizations || []" :key="item.id"><td><input v-model="params.optimizationIds" type="checkbox" :value="item.id" :disabled="busy || item.readOnly" :aria-label="`选择 ${item.name}`"></td><td>{{ item.name }}</td><td>{{ item.readOnly ? '只读检查' : '可预览应用' }}</td><td>{{ item.impact }}</td></tr></tbody></table></div>
    <template v-if="connection && connection.system.uid !== '0'">
      <div v-if="sudoRemembered" class="button-row top-gap"><span role="status">已临时记住 {{ connection.username }} 的sudo密码，后续操作自动复用。</span><button class="btn btn-secondary" :disabled="busy" @click="clearSudo">清除已记住密码</button></div>
      <label v-else class="field top-gap kylin-sudo"><span>sudo密码（{{ connection.username }}）</span><input v-model="sudoPassword" class="input" type="password" maxlength="2048" autocomplete="new-password" :disabled="busy || !!plan" placeholder="通常填写当前SSH用户的登录密码"><small class="muted">通常不是root密码。验证成功后在当前连接内复用；免密sudo可留空。</small></label>
      <p class="muted">sudo密码仅保留在当前页面内存；15分钟未成功提交操作、断开连接、认证失败、刷新或离开页面后清除。</p>
    </template>
    <div class="button-row top-gap"><button class="btn btn-primary" :disabled="busy || !connection" @click="inspect">{{ feature === 'activation' ? '检测激活' : '扫描' }}</button><button v-for="item in directActions" :key="item" class="btn btn-secondary" :disabled="busy || !connection || (item === 'UNINSTALL' && !hasBackup) || (item === 'APPLY' && !params.optimizationIds.length)" :title="item === 'UNINSTALL' ? '恢复到本工具首次变更前，保留原有配置' : undefined" @click="beginAction(item)">{{ actionText(item) }}</button><button v-if="feature !== 'activation'" class="btn btn-secondary" :disabled="busy || !connection" @click="inspect">验证</button></div>
    <p v-if="directActions.includes('UNINSTALL')" class="muted">{{ feature === 'optimization' ? '恢复优化' : '卸载' }}会恢复到本工具首次变更前，保留原有文件和配置，无需手动选择备份。{{ hasBackup ? '' : '当前没有本功能的变更记录。' }}</p>
    <details v-if="hasBackup" class="top-gap"><summary>恢复指定历史备份（高级）</summary><div class="button-row top-gap"><label class="field kylin-backup"><span>本功能历史备份</span><select v-model="params.backupId" class="input" :disabled="busy"><option value="">请选择备份</option><option v-for="item in backups.filter(b => b.feature === feature)" :key="item.backupId" :value="item.backupId">{{ new Date(item.createdAt).toLocaleString() }} · {{ item.backupId }}</option></select></label><button class="btn btn-secondary" :disabled="busy || !connection || !params.backupId" @click="beginAction('RESTORE')">恢复该备份</button></div></details>
    <div v-if="scan" class="kylin-table"><p class="muted">扫描时间 {{ new Date(scan.checkedAt).toLocaleString() }}</p><table><thead><tr><th>检查对象</th><th>结果</th><th>证据 / 说明</th></tr></thead><tbody><tr v-for="item in scan.findings" :key="item.id"><td class="kylin-wrap">{{ item.id }}</td><td>{{ label(item.status) }}</td><td class="kylin-wrap">{{ item.detail }}</td></tr></tbody></table></div>
    <p v-else class="muted top-gap">{{ connection ? '点击扫描查看当前功能的文件与服务状态。' : '连接SSH后即可扫描或操作当前功能。' }}</p>
  </BasePanel>
  <dialog v-if="plan" ref="confirmationDialog" class="kylin-dialog" aria-labelledby="kylin-confirm-title" @cancel.prevent="closePlan">
    <h2 id="kylin-confirm-title">确认{{ actionText(plan.action) }} · {{ currentFeature?.name }}</h2>
    <p><strong>目标：{{ plan.target }}</strong></p><p class="muted">请核对以下影响，确认后才会执行。有效期至 {{ new Date(plan.expiresAt).toLocaleTimeString() }}。</p>
    <ul class="kylin-impacts"><li v-for="impact in plan.impacts" :key="impact">{{ impact }}</li></ul>
    <p v-for="operation in plan.serviceActions" :key="operation">{{ operation }}</p>
    <details class="top-gap"><summary>查看文件内容与校验详情（{{ plan.changes.length }}项）</summary><details v-for="change in plan.changes" :key="change.path" class="top-gap"><summary class="kylin-wrap">{{ change.path }} · 权限 {{ change.mode }}</summary><p class="kylin-wrap muted">原状态 {{ change.beforeHash }}<br>目标状态 {{ change.afterHash }}</p><pre class="kylin-content">{{ change.content }}</pre></details><p class="kylin-wrap muted">{{ feature === 'patch' ? '安装记录ID' : '备份ID' }} {{ plan.backupId }}</p></details>
    <label v-if="feature === 'vnc' && action === 'DEPLOY'" class="field top-gap"><span>本次VNC口令（恰好8个ASCII字符）</span><input v-model="vncPassword" class="input" type="password" minlength="8" maxlength="8" autocomplete="new-password"></label>
    <p class="muted">{{ connection?.system.uid !== '0' ? '已验证的sudo密码可在当前连接内复用；取消本计划不会清除连接内的密码。' : '' }}执行请求不自动重试。</p>
    <div class="button-row top-gap"><button class="btn btn-secondary" autofocus :disabled="busy" @click="closePlan">取消</button><button class="btn btn-primary" :disabled="busy || (feature === 'vnc' && action === 'DEPLOY' && !/^[\x20-\x7e]{8}$/.test(vncPassword))" @click="approve">确认{{ actionText(plan.action) }}</button></div>
  </dialog>
  <BasePanel v-if="task" title="执行任务" class="top-gap"><p role="status" aria-live="polite">{{ label(task.status) }} · {{ label(task.step) }}</p><p>{{ task.message }}</p><p class="kylin-wrap muted">任务 {{ task.taskId }} · {{ task.feature === 'patch' ? '安装记录' : '备份' }} {{ task.backupId }}<template v-if="task.code"> · {{ task.code }}</template></p><button v-if="running" class="btn btn-secondary" :disabled="task.status === 'CANCELLING'" @click="cancel">停止后续步骤</button><p class="muted">停止不保证撤销已发出的远程写操作；断线、超时或部分生效需重新扫描并核对{{ task.feature === 'patch' ? '包安装状态。软件包升级不提供一键回滚。' : '备份。' }}</p></BasePanel>
</template>

<style scoped>
.kylin-wrap { overflow-wrap: anywhere; max-width: 560px; }
.kylin-error { color: var(--danger); overflow-wrap: anywhere; }
.kylin-table { overflow: auto; max-height: 58vh; margin-top: 16px; }
.kylin-table table { min-width: 760px; }
.kylin-content { white-space: pre-wrap; overflow-wrap: anywhere; background: var(--bg); padding: 14px; max-height: 420px; overflow: auto; }
.kylin-tabs { display: flex; flex-wrap: wrap; gap: 8px; padding-bottom: 14px; border-bottom: 1px solid var(--border); }
.kylin-sudo { max-width: 360px; }
.kylin-backup { flex: 1; min-width: 200px; }
.kylin-dialog { width: min(760px, calc(100vw - 32px)); max-height: 88vh; overflow: auto; padding: 24px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); color: var(--fg); }
.kylin-dialog::backdrop { background: rgb(15 23 42 / 40%); }
.kylin-dialog h2 { margin-top: 0; }
.kylin-impacts { padding-left: 20px; }
.kylin-impacts li + li { margin-top: 8px; }
.form-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
@media (max-width: 760px) { .form-grid.three, .form-grid.four { grid-template-columns: 1fr; } }
button:disabled { opacity: .6; cursor: not-allowed; }
</style>
