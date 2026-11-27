<script setup>
import SqlValueInput from './SqlValueInput.vue'
import { newValue, operators } from '../composables/useSql.js'
defineProps({ form: { type: Object, required: true }, mandatory: Boolean })
function changeOperator(item) {
  if (item.operator.startsWith('IS ')) { item.value = null; item.likeMode = null }
  else {
    item.value ||= newValue()
    item.likeMode = item.operator.endsWith('LIKE') ? 'CONTAINS' : null
  }
}
</script>
<template>
  <div class="sql-subsection">
    <div class="sql-section-title"><strong><span class="sql-condition-step">03</span> {{ mandatory ? 'WHERE（必填）' : '筛选条件' }}</strong><div class="button-row">
      <select v-model="form.junction" class="input sql-junction" aria-label="条件组合"><option>AND</option><option>OR</option></select>
      <button class="btn btn-secondary" :disabled="form.conditions.length >= 50" @click="form.conditions.push({ column: '', operator: '=', value: newValue(), likeMode: null })">添加条件</button>
    </div></div>
    <div v-for="(item, index) in form.conditions" :key="index" class="sql-row sql-condition">
      <input v-model="item.column" class="input" :aria-label="`条件列${index + 1}`" placeholder="列名">
      <select v-model="item.operator" class="input" :aria-label="`条件运算符${index + 1}`" @change="changeOperator(item)"><option v-for="op in operators" :key="op">{{ op }}</option></select>
      <SqlValueInput v-if="item.value" v-model="item.value" :label="`条件值${index + 1}`" :allow-null="false" />
      <select v-if="item.operator.endsWith('LIKE')" v-model="item.likeMode" class="input" :aria-label="`LIKE模式${index + 1}`"><option value="CONTAINS">包含（通配符按原文）</option><option value="PATTERN">原始通配模式</option></select>
      <button class="btn btn-ghost" :aria-label="`删除条件${index + 1}`" @click="form.conditions.splice(index, 1)">删除</button>
    </div>
    <p v-if="!form.conditions.length" class="sql-hint">{{ mandatory ? '至少添加一个有效条件，禁止无条件 UPDATE / DELETE。' : '未设置条件，将查询全部符合其他参数的数据。' }}</p>
  </div>
</template>

<style scoped>
.sql-condition-step{font:10px ui-monospace,monospace;color:#93a7ab;background:#f1f5f5;padding:4px 5px;border-radius:4px;margin-right:4px}
</style>
