import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { cronApi } from '../api/cron.js'

export const cronFields = [
  { name: 'second', label: '秒', min: 0, max: 59 }, { name: 'minute', label: '分', min: 0, max: 59 },
  { name: 'hour', label: '时', min: 0, max: 23 }, { name: 'day', label: '日', min: 1, max: 31 },
  { name: 'month', label: '月', min: 1, max: 12 }, { name: 'week', label: '周', min: 0, max: 7 }
]
export function createCronDraft() {
  return reactive({ mode: 'SPRING_6', expression: '0 */5 * * * ?', zoneId: 'Asia/Shanghai', startLocal: '', startOffset: '', count: 10 })
}
const sharedDraft = createCronDraft()
const modeLabel = mode => mode === 'SPRING_6' ? 'Spring 六段' : '五段兼容子集'

export function useCron(api = cronApi, draft = sharedDraft) {
  const capabilities = ref(null), result = ref(null), error = ref(null), pending = ref(false), notice = ref('')
  const tab = ref('templates'), activeField = ref('minute'), rules = reactive({})
  const page = ref(1), pageSize = ref(5)
  const fields = computed(() => draft.mode === 'SPRING_6' ? cronFields : cronFields.slice(1))
  const templates = computed(() => capabilities.value?.templates?.[draft.mode] || [])
  const advanced = computed(() => draft.expression.trim().toUpperCase().split(/\s+/).some(value => /L|W|#/.test(value.replace(/SUN|MON|TUE|WED|THU|FRI|SAT|JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC/g, ''))))
  const pages = computed(() => Math.max(1, Math.ceil((result.value?.times.length || 0) / pageSize.value)))
  const pageRows = computed(() => result.value?.times.slice((page.value - 1) * pageSize.value, page.value * pageSize.value) || [])
  let disposed = false, version = 0, operation, loading
  function resetRules() {
    for (const field of cronFields) rules[field.name] = { kind: field.name === 'second' ? 'FIXED' : 'ALL', values: String(field.min), start: field.min, end: field.max, step: 1 }
  }
  resetRules()
  // 输入变化立即撤销结果资格并中止浏览器等待，服务端不需要启动或停止调度任务。
  function invalidate() {
    version++
    operation?.abort()
    pending.value = false
    result.value = null
    error.value = null
    notice.value = ''
    page.value = 1
  }
  watch(() => [draft.mode, draft.expression, draft.zoneId, draft.startLocal, draft.startOffset, draft.count], invalidate, { flush: 'sync' })
  watch(rules, invalidate, { deep: true, flush: 'sync' })
  watch(() => draft.mode, () => {
    if (draft.mode === 'FIVE_FIELD_SUBSET' && activeField.value === 'second') activeField.value = 'minute'
    notice.value = '模式已切换，请按字段顺序重新编辑，或选择当前模式的模板；不会自动删减字段。'
  }, { flush: 'sync' })
  watch(pageSize, () => { page.value = 1 })
  watch(pages, value => { if (page.value > value) page.value = value })

  async function load() {
    loading?.abort()
    const controller = loading = new AbortController()
    try {
      const data = await api.capabilities(controller.signal)
      if (!disposed && !controller.signal.aborted) { capabilities.value = data; error.value = null }
    } catch (failure) { if (!disposed && !controller.signal.aborted) error.value = failure }
  }
  async function run(generate = false) {
    if (disposed) return
    invalidate()
    const current = version, controller = operation = new AbortController()
    const body = { ...draft, startLocal: draft.startLocal || null, startOffset: draft.startOffset || null }
    pending.value = true
    try {
      let data
      if (generate) {
        delete body.expression
        body.fields = Object.fromEntries(fields.value.map(field => {
          const rule = rules[field.name]
          // 列表必须逐个解析整数；空项不可隐式转换成0。
          const values = ['FIXED', 'LIST'].includes(rule.kind) ? String(rule.values).split(',').map(value => {
            if (!/^\d+$/.test(value.trim())) throw { code: 'INVALID_CRON_FIELD_VALUE', message: field.label + '字段请输入整数，多个值用英文逗号分隔' }
            return Number(value.trim())
          }) : null
          return [field.name, { kind: rule.kind, values, start: rule.start, end: rule.end, step: rule.step }]
        }))
        data = await api.generate(body, controller.signal)
      } else data = await api.analyze(body, controller.signal)
      if (disposed || current !== version || controller.signal.aborted) return
      // 规范化写回会触发同步失效，写回后再赋值本次有效结果。
      draft.expression = data.normalizedExpression
      result.value = data
      page.value = 1
      notice.value = data.outcome === 'COMPLETE' ? '校验通过，已生成执行时间预览' : data.outcome === 'NO_FUTURE_TIME' ? '语法通过，解析器未找到后续执行时间' : '已达到8年预览窗口，结果不足请求条数'
    } catch (failure) { if (!disposed && current === version && !controller.signal.aborted) error.value = failure }
    finally { if (operation === controller) pending.value = false }
  }
  function chooseTemplate(template) { draft.expression = template.expression; return run() }
  function clear() { invalidate(); draft.expression = ''; resetRules() }
  function constrainDate(name) {
    if (['day', 'week'].includes(name) && rules[name].kind !== 'ALL') rules[name === 'day' ? 'week' : 'day'].kind = 'ALL'
  }
  function previewText(data) {
    return [modeLabel(data.mode), data.normalizedExpression, '时区：' + data.zoneId, '起算：' + data.effectiveStart,
      ...data.times.map((row, i) => `${i + 1}. ${row.localDateTime} ${row.weekday} ${row.offset} · ${row.instant}`),
      `结果：${data.returnedCount}/${data.requestedCount}；${data.outcome}；窗口截止：${data.windowEnd}`].join('\n')
  }
  async function copy(preview = false) {
    const data = result.value
    if (!data) return
    const current = version
    try {
      if (!globalThis.navigator?.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(preview ? previewText(data) : data.normalizedExpression)
      if (!disposed && current === version) notice.value = preview ? '已复制全部预览时间' : '已复制表达式'
    } catch {
      if (!disposed && current === version) { error.value = { code: 'CLIPBOARD_UNAVAILABLE', message: '复制失败，请选中下方文本手动复制' }; manualCopy.value = preview ? previewText(data) : data.normalizedExpression }
    }
  }
  const manualCopy = ref('')
  watch(result, () => { manualCopy.value = '' }, { flush: 'sync' })
  onMounted(load)
  onUnmounted(() => { disposed = true; version++; operation?.abort(); loading?.abort() })
  return { draft, capabilities, result, error, pending, notice, fields, templates, advanced, rules, tab, activeField,
    page, pageSize, pages, pageRows, manualCopy, load, run, chooseTemplate, clear, constrainDate, copy }
}
