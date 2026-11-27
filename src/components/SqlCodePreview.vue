<script setup>
import { computed } from 'vue'

const props = defineProps({ sql: { type: String, default: '' }, label: { type: String, default: 'SQL结果' }, indentSize: { type: Number, default: 4 } })
const lineNumbers = computed(() => Array.from({ length: props.sql.split('\n').length }, (_, index) => index + 1).join('\n'))
const highlighted = computed(() => {
  // 仅辅助阅读，绝不改写SQL。大结果直接以文本展示，避免为每个token创建大量DOM。
  if (props.sql.length > 65536) return null
  const pattern = /--[^\r\n]*|\/\*[\s\S]*?\*\/|'(?:''|\\[\s\S]|[^'\\])*'|`(?:``|[^`])*`|"(?:""|[^"])*"|\b[\p{L}_][\p{L}\p{N}_]*\b|\b\d+(?:\.\d+)?\b|[^\w'`"\p{L}\p{N}]+|./gu
  return Array.from(props.sql.matchAll(pattern), match => {
    const text = match[0]
    const kind = /^(--|\/\*)/.test(text) ? 'comment' : text.startsWith("'") ? 'string' : /^[`"]/.test(text) ? 'name'
      : /^(SELECT|FROM|WHERE|ORDER|BY|ASC|DESC|LIMIT|AND|OR|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|NOT|NULL|IS|LIKE|ESCAPE|PRIMARY|KEY|UNIQUE|DEFAULT|AS|IDENTITY|GENERATED|COMMENT|ON|TRUE|FALSE)$/i.test(text) ? 'keyword'
        : /^\d/.test(text) ? 'number' : ''
    return { text, kind }
  })
})
</script>

<template>
  <div class="sql-code-preview" :style="{ tabSize: indentSize }">
    <pre class="sql-line-numbers" aria-hidden="true">{{ lineNumbers }}</pre>
    <pre class="sql-source-code" tabindex="0" :aria-label="label"><code v-if="highlighted"><span v-for="(token, index) in highlighted" :key="index" :class="token.kind">{{ token.text }}</span></code><code v-else>{{ sql }}</code></pre>
  </div>
</template>

<style scoped>
.sql-code-preview{display:flex;flex:1;min-height:0;overflow:auto;padding:8px 0 28px;scrollbar-color:#3c535c transparent;scrollbar-width:thin}
.sql-code-preview pre{margin:0;font:12px/2.1 'Cascadia Code','Consolas',ui-monospace,monospace;white-space:pre}
.sql-line-numbers{position:sticky;left:0;align-self:flex-start;flex:none;min-width:52px;padding-right:18px;text-align:right;color:#5b7a85;background:#14252c;user-select:none}
.sql-source-code{padding-right:24px;color:#c2d5db;min-width:0}.sql-source-code code{font:inherit}
.keyword{color:#6cc3cf}.string{color:#b2cd98}.name{color:#e2caa0}.number{color:#c49ed9}.comment{color:#7f9b92}
@media(min-width:1700px){.sql-code-preview pre{font-size:14px}}
@media(max-width:1050px){.sql-code-preview{min-height:260px}}
</style>
