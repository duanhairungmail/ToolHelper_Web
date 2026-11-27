import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { sqlApi } from '../api/sql.js'

export const statementTypes = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE_TABLE']
export const operators = ['=', '!=', '>', '<', '>=', '<=', 'LIKE', 'NOT LIKE', 'IS NULL', 'IS NOT NULL']
export const columnTypes = ['SMALLINT', 'INTEGER', 'BIGINT', 'DECIMAL', 'CHAR', 'VARCHAR', 'TEXT', 'DATE', 'TIME', 'TIMESTAMP', 'BOOLEAN']
export const newValue = (type = 'STRING') => ({ type, value: type === 'NULL' ? null : type === 'BOOLEAN' ? 'false' : '' })
export const newColumn = () => ({ name: '', type: 'VARCHAR', length: 128, precision: null, scale: null, nullable: true, primaryKey: false, unique: false, identity: false, defaultValue: null })
export const newForm = () => ({ schema: '', table: '', allColumns: true, columns: [], values: [], conditions: [], orders: [], limit: '', comment: '', junction: 'AND' })
export function createSqlDraft() {
  return reactive({ tab: 'generate', dialect: 'MYSQL', escapeMode: 'BACKSLASH', statementType: 'SELECT', source: '', keywordCase: 'UPPER', indentSize: 4,
    forms: Object.fromEntries(statementTypes.map(type => [type, newForm()])) })
}
const sharedDraft = createSqlDraft()
const copyValue = value => value ? { type: value.type, value: value.type === 'NULL' ? null : value.value } : null

export function generationPayload(draft) {
  const form = draft.forms[draft.statementType]
  const base = { dialect: draft.dialect, escapeMode: draft.escapeMode, statementType: draft.statementType, schema: form.schema || null, table: form.table }
  const conditions = () => form.conditions.map(row => ({ column: row.column, operator: row.operator,
    value: row.operator.startsWith('IS ') ? null : copyValue(row.value), likeMode: row.operator.endsWith('LIKE') ? row.likeMode : null }))
  const values = () => form.values.map(row => ({ column: row.column, value: copyValue(row.value) }))
  switch (draft.statementType) {
    case 'SELECT': return { ...base, select: { allColumns: form.allColumns, columns: form.allColumns ? [] : form.columns.map(x => x.name), junction: form.junction, conditions: conditions(), orders: form.orders.map(row => ({ ...row })), limit: form.limit || null } }
    case 'INSERT': return { ...base, insert: { values: values() } }
    case 'UPDATE': return { ...base, update: { values: values(), junction: form.junction, conditions: conditions() } }
    case 'DELETE': return { ...base, delete: { junction: form.junction, conditions: conditions() } }
    case 'CREATE_TABLE': return { ...base, create: { columns: form.columns.map(row => ({ ...row, defaultValue: copyValue(row.defaultValue) })), comment: form.comment || null } }
    default: throw new Error('不支持的语句类型')
  }
}

export function useSql(api = sqlApi, draft = sharedDraft) {
  const form = computed(() => draft.forms[draft.statementType])
  const generated = ref(null), formatted = ref(null), capabilities = ref(null), error = ref(null), notice = ref('')
  const generating = ref(false), formatting = ref(false), loading = ref(false)
  const sourceBytes = computed(() => new TextEncoder().encode(draft.source).length)
  let disposed = false, version = 0, importVersion = 0, generationController, formatController, loadController
  function invalidate() {
    version++; generationController?.abort(); formatController?.abort()
    generating.value = false; formatting.value = false; generated.value = null; formatted.value = null
    error.value = null; notice.value = ''
  }
  watch(() => [draft.dialect, draft.escapeMode, draft.statementType, draft.source, draft.keywordCase, draft.indentSize, draft.forms], invalidate, { deep: true, flush: 'sync' })
  watch(() => draft.dialect, value => { draft.escapeMode = value === 'MYSQL' ? 'BACKSLASH' : 'STANDARD' }, { flush: 'sync' })
  async function load() {
    loadController?.abort(); const controller = loadController = new AbortController(); loading.value = true
    try { const result = await api.capabilities(controller.signal); if (!disposed && !controller.signal.aborted) { capabilities.value = result; error.value = null } }
    catch (failure) { if (!disposed && !controller.signal.aborted) error.value = failure }
    finally { if (!disposed && !controller.signal.aborted) loading.value = false }
  }
  async function run(kind) {
    if (disposed || generating.value || formatting.value) return
    invalidate(); const current = version, controller = new AbortController()
    const isGenerate = kind === 'generate', pending = isGenerate ? generating : formatting
    if (isGenerate) generationController = controller; else formatController = controller
    if (!isGenerate && sourceBytes.value > (capabilities.value?.maxSqlBytes || 262144)) { error.value = new Error('SQL 输入超过 256 KiB'); return }
    const body = isGenerate ? generationPayload(draft) : { dialect: draft.dialect, escapeMode: draft.escapeMode, sql: draft.source, keywordCase: draft.keywordCase, indentSize: draft.indentSize }
    pending.value = true
    try {
      const data = await api[kind](body, controller.signal)
      if (!disposed && current === version && !controller.signal.aborted) {
        if (!data || typeof data.sql !== 'string') throw new Error('服务未返回有效SQL结果')
        ;(isGenerate ? generated : formatted).value = data
      }
    } catch (failure) { if (!disposed && current === version && !controller.signal.aborted) error.value = failure }
    finally { if (!disposed && current === version) pending.value = false }
  }
  async function copy(text) {
    if (!text) return
    try { await navigator.clipboard.writeText(text); if (!disposed) notice.value = '已复制到剪贴板。' }
    catch { if (!disposed) notice.value = '浏览器未允许剪贴板访问，请选中文本后手动复制。' }
  }
  function download(text, suffix) {
    if (!text) return
    const url = URL.createObjectURL(new Blob([text], { type: 'text/sql;charset=utf-8' })), link = document.createElement('a')
    link.href = url; link.download = `toolhelper-${draft.dialect.toLowerCase()}-${suffix}-${new Date().toISOString().replaceAll(':', '-')}.sql`
    document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  async function importFile(file) {
    if (!file) return
    const current = ++importVersion, source = draft.source, revision = version
    if (!file.name.toLowerCase().endsWith('.sql') || file.size > 262144) { error.value = new Error('请选择不超过256 KiB的 .sql 文件'); return }
    try {
      const buffer = await file.arrayBuffer()
      if (disposed || current !== importVersion || draft.source !== source || revision !== version) return
      const text = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
      if (new TextEncoder().encode(text).length > 262144) throw new Error('文件超过256 KiB')
      draft.source = text; notice.value = '已导入UTF-8 SQL文本。'
    } catch { if (!disposed && current === importVersion) error.value = new Error('文件读取失败或不是有效 UTF-8 文本；原输入已保留') }
  }
  function sendToFormatter(confirmReplace = () => window.confirm('格式化输入已有内容，是否替换？')) {
    const sql = generated.value?.sql
    if (!sql || (draft.source && draft.source !== sql && !confirmReplace())) return
    draft.source = sql; draft.tab = 'format'
  }
  function resetForm() { invalidate(); draft.forms[draft.statementType] = newForm() }
  function clearInput() { importVersion++; draft.source = '' }
  onMounted(load)
  onUnmounted(() => { disposed = true; importVersion++; invalidate(); loadController?.abort() })
  return { draft, form, generated, formatted, capabilities, error, notice, generating, formatting, loading, sourceBytes, load,
    runGenerate: () => run('generate'), runFormat: () => run('format'), copy, download, importFile, sendToFormatter, resetForm, clearInput }
}
