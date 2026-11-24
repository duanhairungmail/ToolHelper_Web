<script setup>
import { computed, onUnmounted } from 'vue'
import BasePanel from './BasePanel.vue'
import LogDrawer from './LogDrawer.vue'
import { useLogDrawer } from '@/composables/useLogDrawer'
import { useDatabasePlugin } from '@/composables/useDatabasePlugin'
import { useDatabaseConnection } from '@/composables/useDatabaseConnection'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const { expanded, lines, append, clear } = useLogDrawer()
const { snapshot, available, checking, task, error, busy, installed, usable, badge, refresh, perform } = useDatabasePlugin({ append, setStatus: store.setStatus.bind(store) })
const { type, host, port, username, database, connectionName, ssl, transferPassword, password, formError, connect, clearPassword } = useDatabaseConnection(perform)
const progress = computed(() => task.value?.totalBytes ? Math.min(100, Math.floor(task.value.downloadedBytes / task.value.totalBytes * 100)) : null)
onUnmounted(clearPassword)
</script>

<template>
  <BasePanel title="插件管理" description="DBX · Windows x64 便携版 · 由本机 Java 服务管理">
    <template #actions><span class="badge" :class="{ warn: !installed || busy }" role="status">{{ badge }}</span></template>
    <div class="button-row">
      <button class="btn btn-primary" :disabled="!usable || snapshot?.state !== 'NOT_INSTALLED'" @click="perform('install')">下载插件</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('launch')">启动外挂</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('update')">插件更新</button>
      <button class="btn btn-secondary" :disabled="!usable || !installed" @click="perform('uninstall')">删除插件</button>
      <button class="btn btn-secondary" :disabled="!usable" @click="perform('open-directory')">打开所在目录</button>
      <button class="btn btn-ghost" :disabled="checking || busy" @click="refresh">刷新状态</button>
    </div>
    <p v-if="available && snapshot" class="muted plugin-meta">发行版本：{{ snapshot.version || '—' }}<template v-if="snapshot.packageVersion"> · 包内版本：{{ snapshot.packageVersion }}</template><br>进程：{{ ({ RUNNING: '检测到运行中', NOT_OBSERVED: '未检测到运行', UNKNOWN: '无法确认' })[snapshot.running?.state] }}<br>目录：{{ snapshot.directory }}<br>用户数据：{{ snapshot.userDataDirectory }}</p>
    <p v-if="snapshot?.busy && snapshot?.activePluginId !== 'dbx'" class="muted">{{ snapshot.activePluginId }} 正在执行任务，请等待完成。</p>
    <p v-if="available && !snapshot?.platformSupported" class="plugin-error">请在 Windows x64 登录用户的桌面中运行 Java 服务。</p>
    <p v-if="snapshot?.state === 'BROKEN' || snapshot?.state === 'RECOVERY_REQUIRED'" class="plugin-error">{{ snapshot.message }}。可打开管理目录核对。</p>
    <div v-if="task && busy" class="top-gap" role="status" aria-live="polite">
      <div>{{ task.message }} · {{ task.phase }}<span v-if="progress !== null"> · {{ progress }}%</span></div>
      <progress v-if="progress !== null" class="plugin-progress" :value="progress" max="100" aria-label="DBX 下载进度" />
      <span class="muted">任务：{{ task.taskId }}</span>
    </div>
    <p v-if="error" class="plugin-error" role="alert">{{ error.message }}<br><small>{{ error.code }}<template v-if="error.requestId"> · 请求标识：{{ error.requestId }}</template></small></p>
  </BasePanel>
  <BasePanel title="数据库外挂连接" description="Java 将本次参数直接传入 DBX，一次性发起 MySQL / PostgreSQL 连接。">
    <form @submit.prevent="connect">
      <fieldset class="plugin-fields" :disabled="!usable || !installed">
        <div class="form-grid">
          <label class="field"><span>类型</span><select v-model="type" class="input"><option value="MYSQL">MySQL</option><option value="POSTGRESQL">PostgreSQL</option></select></label>
          <label class="field"><span>主机</span><input v-model="host" class="input" placeholder="IP 或 DNS 主机名" required maxlength="253" autocomplete="off"></label>
          <label class="field"><span>端口</span><input v-model="port" class="input" type="number" min="1" max="65535" step="1" required></label>
          <label class="field"><span>用户名</span><input v-model="username" class="input" required maxlength="256" autocomplete="off"></label>
          <label class="field"><span>数据库名（可选）</span><input v-model="database" class="input" maxlength="256" autocomplete="off"></label>
          <label class="field"><span>连接名（可选）</span><input v-model="connectionName" class="input" maxlength="256" autocomplete="off" placeholder="仅用于本次 DBX 连接"></label>
        </div>
        <div class="button-row top-gap">
          <label><input v-model="ssl" type="checkbox"> 启用 TLS（ssl）</label>
          <label><input v-model="transferPassword" type="checkbox"> 本次传递密码</label>
        </div>
        <label v-if="transferPassword" class="field top-gap"><span>密码（仅本次，可为空）</span><input v-model="password" class="input" type="password" maxlength="2048" autocomplete="new-password"></label>
        <p v-if="transferPassword" class="plugin-error">密码会进入 DBX 进程命令行，本机有权限的程序可能读取；URI 编码不是加密。提交后立即清空，不自动重试。</p>
        <p v-if="formError" class="plugin-error" role="alert">{{ formError }}</p>
        <button class="btn btn-primary top-gap" type="submit">外挂连接</button>
      </fieldset>
    </form>
    <p class="muted">默认不传密码，认证可能需要在 DBX 中完成。接口成功仅表示已提交请求，连接、TLS 和认证结果请查看 DBX。更新或删除前请退出 DBX；完整 data（含密钥）会保留。</p>
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
