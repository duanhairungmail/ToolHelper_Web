<script setup>
import { computed } from 'vue'
import BasePanel from './BasePanel.vue'
import { useCron } from '../composables/useCron.js'

const { draft, capabilities, result, error, pending, notice, fields, templates, advanced, rules, tab, activeField,
  page, pageSize, pages, pageRows, manualCopy, load, run, chooseTemplate, clear, constrainDate, copy } = useCron()
const field = computed(() => fields.value.find(item => item.name === activeField.value) || fields.value[0])
const rule = computed(() => rules[field.value.name])
</script>

<template>
  <div class="cron-page">
    <BasePanel title="表达式编辑" description="只校验和计算执行时间；不会运行命令或创建定时任务。">
      <template #actions><button class="btn btn-ghost" :disabled="pending" @click="load">{{ capabilities ? '刷新服务能力' : '连接本机服务' }}</button></template>
      <div class="cron-editor">
        <label class="field"><span>表达式模式</span><select v-model="draft.mode" class="input"><option value="SPRING_6">Spring 六段 · 秒 分 时 日 月 周</option><option value="FIVE_FIELD_SUBSET">五段兼容子集 · 分 时 日 月 周</option></select></label>
        <label class="field cron-expression"><span>Cron 表达式</span><input v-model="draft.expression" class="input" maxlength="128" spellcheck="false" :placeholder="draft.mode === 'SPRING_6' ? '0 */5 * * * ?' : '*/5 * * * *'" @keyup.enter="run()"></label>
      </div>
      <div class="button-row top-gap"><button class="btn btn-primary" :disabled="pending || !draft.expression.trim()" @click="run()">{{ pending ? '计算中…' : '校验并预览' }}</button><button class="btn btn-secondary" :disabled="!result" @click="copy()">复制表达式</button><button class="btn btn-ghost" @click="clear">清空</button></div>
      <p v-if="notice" class="cron-notice" role="status">{{ notice }}</p>
      <p v-if="error" class="validation-error" role="alert">{{ error.message }} <small>{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></p>
      <textarea v-if="manualCopy" :value="manualCopy" class="input textarea top-gap" readonly aria-label="手动复制文本"></textarea>
    </BasePanel>

    <div class="cron-middle">
      <BasePanel title="规则构建">
        <div class="button-row cron-tabs"><button class="btn" :class="tab === 'templates' ? 'btn-secondary' : 'btn-ghost'" @click="tab = 'templates'">常用模板</button><button class="btn" :class="tab === 'generator' ? 'btn-secondary' : 'btn-ghost'" @click="tab = 'generator'">基础生成器</button></div>
        <div v-if="tab === 'templates'" class="cron-templates"><button v-for="template in templates" :key="template.expression" class="cron-template" :disabled="pending" @click="chooseTemplate(template)"><span>{{ template.name }}</span><code>{{ template.expression }}</code></button><p v-if="!templates.length" class="muted">请连接本机服务以加载当前模式模板。</p></div>
        <template v-else>
          <p class="muted">{{ advanced ? '高级表达式，请在手动编辑中修改。生成器使用独立草稿，点击下方按钮才会替换当前表达式。' : '生成器使用独立草稿，生成时替换当前表达式并校验。日与周自动互斥。' }}</p>
          <div class="button-row cron-fields"><button v-for="item in fields" :key="item.name" class="btn" :class="activeField === item.name ? 'btn-secondary' : 'btn-ghost'" @click="activeField = item.name">{{ item.label }}</button></div>
          <div class="form-grid two top-gap">
            <label class="field"><span>{{ field.label }}规则（{{ field.min }}–{{ field.max }}）</span><select v-model="rule.kind" class="input" @change="constrainDate(field.name)"><option value="ALL">所有值</option><option value="FIXED">固定值</option><option value="LIST">多个值</option><option value="RANGE">范围</option><option value="STEP">起点与步长</option></select></label>
            <label v-if="['FIXED', 'LIST'].includes(rule.kind)" class="field"><span>{{ rule.kind === 'LIST' ? '多个整数，用英文逗号分隔' : '固定整数值' }}</span><input v-model="rule.values" class="input" placeholder="例如 1,3,5"></label>
            <template v-if="['RANGE', 'STEP'].includes(rule.kind)"><label class="field"><span>起点</span><input v-model.number="rule.start" type="number" class="input" :min="field.min" :max="field.max"></label><label v-if="rule.kind === 'RANGE'" class="field"><span>终点</span><input v-model.number="rule.end" type="number" class="input" :min="field.min" :max="field.max"></label><label v-else class="field"><span>步长</span><input v-model.number="rule.step" type="number" class="input" min="1" :max="field.max - field.min + 1"></label></template>
          </div>
          <p class="muted cron-rule-summary">{{ fields.map(item => item.label + '：' + ({ ALL: '*', FIXED: rules[item.name].values, LIST: rules[item.name].values, RANGE: rules[item.name].start + '-' + rules[item.name].end, STEP: rules[item.name].start + '/' + rules[item.name].step })[rules[item.name].kind]).join(' · ') }}</p>
          <button class="btn btn-primary top-gap" :disabled="pending" @click="run(true)">{{ advanced ? '使用生成器替换并预览' : '生成并预览' }}</button>
        </template>
      </BasePanel>
      <BasePanel title="字段说明">
        <table v-if="result"><thead><tr><th>字段</th><th>规则</th><th>说明</th></tr></thead><tbody><tr v-for="item in result.fields" :key="item.name"><td>{{ item.label }}</td><td><code>{{ item.value }}</code></td><td>{{ item.description }}</td></tr></tbody></table>
        <div v-else class="empty-state">校验通过后显示当前表达式的字段解释。</div>
        <ul class="cron-tips muted"><li>星期 0/7 为周日，1 为周一；日与周不能同时设置具体条件。</li><li>五段只支持数字及基础规则；六段不是完整 Quartz 兼容模式，不支持年份或宏。</li><li>步进按字段范围计算；周一至周五不调整法定节假日，每月31日不自动顺延。</li></ul>
      </BasePanel>
    </div>

    <BasePanel title="未来执行时间">
      <template #actions><button class="btn btn-ghost" :disabled="!result" @click="copy(true)">复制全部预览</button></template>
      <div class="form-grid cron-time-settings">
        <label class="field"><span>时区</span><select v-model="draft.zoneId" class="input"><option v-for="zone in capabilities?.zones || ['Asia/Shanghai', 'UTC']" :key="zone">{{ zone }}</option></select></label>
        <label class="field"><span>本地起算时间（留空使用服务端当前时间）</span><input v-model="draft.startLocal" type="datetime-local" step="1" class="input"></label>
        <label class="field"><span>起算 UTC 偏移（重复时间时必填）</span><input v-model="draft.startOffset" class="input" placeholder="例如 -04:00，平时留空" maxlength="9"></label>
        <label class="field"><span>预览次数（1–100）</span><input v-model.number="draft.count" type="number" class="input" min="1" max="100" list="cron-count-options"><datalist id="cron-count-options"><option :value="5"/><option :value="10"/><option :value="20"/><option :value="50"/></datalist></label>
      </div>
      <p v-if="result" class="muted cron-window">起算：{{ result.effectiveStart }} · 严格晚于起点 · {{ result.returnedCount }}/{{ result.requestedCount }} 条 · 窗口截止：{{ result.windowEnd }}</p>
      <p class="muted cron-window">夏令时会跳过不存在的执行时间；回拨时同一本地时间可能执行两次，以 UTC 偏移区分。修改时间参数后请重新“校验并预览”。</p>
      <div class="cron-table"><table><thead><tr><th>序号</th><th>所选时区日期时间</th><th>星期</th><th>UTC 偏移</th><th>UTC 瞬间</th></tr></thead><tbody><tr v-for="(row, index) in pageRows" :key="row.instant"><td>{{ (page - 1) * pageSize + index + 1 }}</td><td><code>{{ row.localDateTime.replace('T', ' ') }}</code></td><td>{{ row.weekday }}</td><td>{{ row.offset }}</td><td><code>{{ row.instant }}</code></td></tr><tr v-if="!pageRows.length"><td colspan="5" class="empty-state">{{ result ? '语法通过，但未找到窗口内执行时间。' : '尚无有效预览，请校验表达式。' }}</td></tr></tbody></table></div>
      <div class="button-row cron-pagination"><span class="muted">共 {{ result?.times.length || 0 }} 条 · 第 {{ page }}/{{ pages }} 页</span><label class="cron-page-size"><span>每页</span><select v-model.number="pageSize" class="input"><option v-for="size in [5, 10, 20, 50]" :key="size" :value="size">{{ size }} 条</option></select></label><button class="btn btn-ghost" :disabled="page <= 1" @click="page--">上一页</button><button class="btn btn-ghost" :disabled="page >= pages" @click="page++">下一页</button></div>
    </BasePanel>
  </div>
</template>

<style scoped>
.cron-page { display: flex; flex-direction: column; gap: 16px; }
.cron-editor { display: grid; grid-template-columns: minmax(240px, .6fr) minmax(0, 1.4fr); gap: 16px; }
.cron-expression input { font-family: var(--font-mono); }
.cron-middle { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.cron-middle > * { min-width: 0; }
.cron-tabs { margin-bottom: 14px; }
.cron-templates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.cron-template { display: grid; text-align: left; gap: 5px; padding: 10px 12px; color: var(--fg); border: 1px solid var(--border); border-radius: 7px; background: var(--bg); cursor: pointer; }
.cron-page > .panel + .panel, .cron-middle > .panel + .panel { margin-top: 0; }
.cron-template:hover { border-color: var(--accent); }
.cron-template:disabled { opacity: .5; cursor: default; }
.cron-template code { overflow-wrap: anywhere; color: var(--muted); font-size: 11px; }
.cron-tips { padding-left: 18px; line-height: 1.8; font-size: 12px; }
.cron-notice, .cron-window { margin: 12px 0 0; overflow-wrap: anywhere; }
.cron-rule-summary { margin: 16px 0 0; overflow-wrap: anywhere; font-family: var(--font-mono); }
.cron-time-settings { grid-template-columns: 1fr 1.4fr 1fr .65fr; }
.cron-table { overflow-x: auto; margin-top: 16px; }
.cron-table th { white-space: nowrap; }
.cron-page code { font-family: var(--font-mono); }
.cron-pagination { justify-content: flex-end; margin-top: 14px; align-items: center; }
.cron-pagination > span { margin-right: auto; }
.cron-page-size { display: flex; align-items: center; gap: 8px; }
.cron-page-size select { width: auto; }
@media (max-width: 1100px) { .cron-time-settings { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 800px) { .cron-middle, .cron-editor { grid-template-columns: 1fr; } }
@media (max-width: 550px) { .cron-templates, .cron-time-settings { grid-template-columns: 1fr; } }
</style>
