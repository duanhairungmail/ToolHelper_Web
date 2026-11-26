<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { kylinLabel } from '../composables/useKylinOps.js'
const props = defineProps({ mode: String, form: Object, task: Object, qrUrl: String, running: Boolean, pending: Boolean,
  uncertain: Boolean, submitted: Boolean, target: String, error: Object })
const emit = defineEmits(['close', 'start', 'submit', 'cancel', 'export', 'refresh'])
const dialog = ref(null), large = ref(false), now = ref(Date.now())
const clock = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(clock))
watch(() => props.mode, async mode => { await nextTick(); if (mode && !dialog.value?.open) dialog.value?.showModal() }, { immediate: true })
const waiting = computed(() => props.task?.step === 'WAITING_CODE' && props.running)
const seconds = computed(() => Math.max(0, Math.ceil((Date.parse(props.task?.expiresAt) - now.value) / 1000)) || 0)
const fields = computed(() => [['stationName', '变电站名称', 128], ['serialNumber', '服务序列号', 128], ['activationKey', '激活密钥', 512],
  ...(props.mode === 'export' ? [['activationCode', '激活码', 512]] : [])])
const locked = computed(() => props.running || props.uncertain || props.pending)
const started = computed(() => props.mode === 'activate' && (!!props.task || props.uncertain))
</script>

<template>
  <dialog ref="dialog" class="activation-dialog" aria-labelledby="activation-title" @cancel.prevent="!locked && emit('close')">
    <h2 id="activation-title">{{ mode === 'export' ? '导出激活信息' : '密钥激活' }}</h2>
    <p class="wrap"><strong>目标：{{ target }}</strong></p>
    <template v-if="!started || mode === 'export'">
      <p v-if="mode === 'activate'">确认后通过 sudo kylin-system-verify 获取本次二维码。请用手机微信扫码取得激活码，再回到此窗口提交。</p>
      <p v-else>可补录变电站名称、服务序列号、激活密钥及激活码；主机SN由目标系统自动绑定，本工具不要求手工填写。补录不会执行激活。</p>
      <form @submit.prevent="mode === 'export' ? emit('export') : emit('start')">
        <div class="activation-fields">
          <label v-for="[field, title, limit] in fields" :key="field" class="field"><span>{{ title }}{{ mode === 'activate' || ['stationName', 'serialNumber'].includes(field) ? '（必填）' : '' }}</span>
            <input v-model="form[field]" class="input" :type="field === 'activationKey' && mode === 'activate' ? 'password' : 'text'" :maxlength="limit"
              :required="mode === 'activate' || ['stationName', 'serialNumber'].includes(field)" :disabled="locked" autocomplete="off">
          </label>
        </div>
        <p v-if="mode === 'activate'" class="muted">服务序列号预填目标KEY；不同时会再次提示确认。已激活系统禁止再次激活，续期或取消激活请手动操作。</p>
        <p v-else class="muted">文件包含明文激活密钥和激活码，仅保留当前页面当前连接的单条记录。</p>
        <p v-if="error" role="alert" class="activation-error wrap">{{ error.message }}<br>{{ error.code }}</p>
        <div class="button-row"><button type="button" class="btn btn-secondary" :disabled="locked" @click="emit('close')">取消</button><button class="btn btn-primary" :disabled="locked">{{ mode === 'export' ? '导出.xlsx' : '确定并获取二维码' }}</button></div>
      </form>
    </template>
    <template v-else>
      <p role="status">{{ uncertain ? '请求结果待确认，请刷新状态查询原任务，勿重复启动。' : kylinLabel(task?.step) }}</p><button type="button" class="btn btn-secondary" :disabled="pending" @click="emit('refresh')">刷新状态</button>
      <p class="wrap">{{ task?.message }}</p>
      <template v-if="waiting && seconds > 0">
        <p>请用手机微信扫描下方二维码，按微信页面提示输入激活密钥。获取激活码后填入本窗口。</p>
        <div v-if="qrUrl" class="qr-area"><img :src="qrUrl" alt="当前目标系统的注册信息二维码" :class="{ large }"><button class="btn btn-secondary" @click="large = !large">{{ large ? '缩小二维码' : '放大二维码' }}</button></div>
        <p v-else>正在读取二维码图片；可点击“刷新状态”重试读取。</p>
        <p>等待剩余 {{ Math.floor(seconds / 60) }}分{{ seconds % 60 }}秒；扫码前不会跳过二维码步骤。</p>
        <form @submit.prevent="emit('submit')"><label class="field"><span>微信生成的激活码（必填）</span><input v-model="form.activationCode" class="input" maxlength="512" required autocomplete="off" :disabled="pending || submitted"></label><button class="btn btn-primary top-gap" :disabled="pending || submitted || !qrUrl">提交激活码并激活</button></form>
      </template>
      <p v-if="error" role="alert" class="activation-error wrap">{{ error.message }}<br>{{ error.code }}</p>
      <div class="button-row top-gap"><button v-if="running" class="btn btn-secondary" :disabled="pending || task?.status === 'CANCELLING'" @click="emit('cancel')">取消并停止后续步骤</button><button v-else class="btn btn-secondary" :disabled="pending || uncertain" @click="emit('close')">关闭</button></div>
      <p class="muted">提交后不自动重试。已提交激活码时，取消或断线无法保证撤销许可变更，请重新激活检测。</p>
    </template>
  </dialog>
</template>

<style scoped>
.activation-dialog { width: min(760px, calc(100vw - 32px)); max-height: 88vh; overflow: auto; padding: 24px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); color: var(--fg); }
.activation-dialog::backdrop { background: rgb(15 23 42 / 40%); }
.activation-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.qr-area { display: flex; flex-direction: column; align-items: center; gap: 10px; overflow: auto; }
.qr-area img { width: 320px; height: auto; max-width: 100%; image-rendering: pixelated; background: white; }
.qr-area img.large { width: 600px; }
.wrap { overflow-wrap: anywhere; }
.activation-error { color: var(--danger); }
@media (max-width: 600px) { .activation-fields { grid-template-columns: 1fr; } }
</style>
