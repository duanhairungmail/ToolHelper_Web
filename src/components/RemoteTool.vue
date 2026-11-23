<script setup>
import { computed, ref } from 'vue'
import BasePanel from './BasePanel.vue'
import LogDrawer from './LogDrawer.vue'
import { useLogDrawer } from '@/composables/useLogDrawer'
import { useRemotePlugin } from '@/composables/useRemotePlugin'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const { expanded, lines, append, clear } = useLogDrawer()
const { snapshot, available, checking, task, error, busy, installed, usable, badge, refresh, perform } = useRemotePlugin({ append, setStatus: store.setStatus.bind(store) })
const protocol = ref('SSH')
const host = ref('')
const port = ref(22)
const username = ref('')
const formError = ref('')
const progress = computed(() => task.value?.totalBytes
  ? Math.min(100, Math.floor(task.value.downloadedBytes / task.value.totalBytes * 100)) : null)

function changeProtocol() {
  port.value = ({ SSH: 22, SFTP: 22, RDP: 3389, VNC: 5900 })[protocol.value]
}

function connect() {
  formError.value = ''
  if (!host.value.trim() || !Number.isInteger(Number(port.value)) || Number(port.value) < 1 || Number(port.value) > 65535
    || (protocol.value !== 'VNC' && !username.value.trim())) {
    formError.value = '请填写 IP 地址、1～65535 的端口和用户名（VNC 用户名可选）。'
    return
  }
  perform('connect', { protocol: protocol.value, host: host.value.trim(), port: Number(port.value), username: username.value.trim() })
}
</script>

<template>
  <BasePanel title="插件管理" description="electerm · Windows x64 · 由本机 Java 服务管理">
    <template #actions><span class="badge" :class="{ warn: !installed || busy }" role="status">{{ badge }}</span></template>
    <div class="button-row">
      <button class="btn btn-primary" :disabled="!usable || snapshot?.state !== 'NOT_INSTALLED'" @click="perform('install')">下载插件</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('launch')">启动外挂</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('update')">插件更新</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('uninstall')">删除插件</button>
      <button class="btn btn-secondary" :disabled="!usable" @click="perform('open-directory')">打开所在目录</button>
      <button class="btn btn-ghost" :disabled="checking || busy" @click="refresh">刷新状态</button>
    </div>
    <p v-if="available && snapshot" class="muted plugin-meta">版本：{{ snapshot.version || '—' }} · 进程：{{ ({ RUNNING: '检测到运行中', NOT_OBSERVED: '未检测到运行', UNKNOWN: '无法确认' })[snapshot.running?.state] }}<br>目录：{{ snapshot.directory }}</p>
    <p v-if="available && !snapshot?.platformSupported" class="plugin-error">当前平台不支持本阶段功能，请在 Windows x64 登录用户的桌面中运行 Java 服务。</p>
    <p v-if="snapshot?.state === 'BROKEN' || snapshot?.state === 'RECOVERY_REQUIRED'" class="plugin-error">{{ snapshot.message }}。可打开管理目录核对。</p>
    <div v-if="task && busy" class="top-gap" role="status" aria-live="polite">
      <div>{{ task.message }} · {{ task.phase }}<span v-if="progress !== null"> · {{ progress }}%</span></div>
      <progress v-if="progress !== null" class="plugin-progress" :value="progress" max="100" aria-label="插件下载进度" />
      <span class="muted">任务：{{ task.taskId }}</span>
    </div>
    <p v-if="error" class="plugin-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · 请求标识：{{ error.requestId }}</template></small></p>
  </BasePanel>
  <BasePanel title="外挂连接" description="参数由 Java 直接传入 electerm；密码、密钥、MFA 或首次信任确认在外挂中完成。">
    <form @submit.prevent="connect">
      <fieldset class="plugin-fields" :disabled="!usable || !installed">
        <div class="form-grid">
          <label class="field"><span>协议</span><select v-model="protocol" class="input" @change="changeProtocol"><option>SSH</option><option>SFTP</option><option>RDP</option><option>VNC</option></select></label>
          <label class="field"><span>IP 地址</span><input v-model="host" class="input" placeholder="IPv4 或 IPv6" required maxlength="45" autocomplete="off"></label>
          <label class="field"><span>端口</span><input v-model="port" class="input" type="number" min="1" max="65535" step="1" required></label>
          <label class="field"><span>用户名{{ protocol === 'VNC' ? '（可选）' : '' }}</span><input v-model="username" class="input" :required="protocol !== 'VNC'" maxlength="256" autocomplete="off" placeholder="user 或 DOMAIN\user"></label>
        </div>
        <p v-if="formError" class="plugin-error" role="alert">{{ formError }}</p>
        <button class="btn btn-primary top-gap" type="submit">外挂连接</button>
      </fieldset>
    </form>
    <p class="muted">接口返回表示已提交连接请求，实际远程连接和认证结果请查看 electerm。当前适配版本：{{ snapshot?.supportedVersions?.join('、') || '查询中' }}；四协议真实服务验收待完成。</p>
  </BasePanel>
  <LogDrawer v-model:expanded="expanded" :lines="lines" @clear="clear" />
</template>

<style scoped>
.plugin-meta, .plugin-error { overflow-wrap: anywhere; }
.plugin-error { color: var(--danger); }
.plugin-fields { min-width: 0; margin: 0; padding: 0; border: 0; }
.plugin-fields:disabled { opacity: .65; }
.btn:disabled { cursor: not-allowed; opacity: .5; }
.plugin-progress { display: block; width: 100%; margin: 8px 0; accent-color: var(--accent); }
</style>
