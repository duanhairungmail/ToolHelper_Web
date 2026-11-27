<script setup>
import { newValue } from '../composables/useSql.js'
const props = defineProps({ modelValue: { type: Object, required: true }, label: { type: String, default: '值' }, allowNull: { type: Boolean, default: true } })
const emit = defineEmits(['update:modelValue'])
function changeType(event) { emit('update:modelValue', newValue(event.target.value)) }
function changeValue(event) { emit('update:modelValue', { ...props.modelValue, value: event.target.value }) }
</script>
<template>
  <select class="input" :value="modelValue.type" :aria-label="`${label}类型`" @change="changeType">
    <option value="STRING">字符串</option><option value="NUMBER">数值</option><option value="BOOLEAN">布尔值</option><option v-if="allowNull" value="NULL">NULL</option>
  </select>
  <select v-if="modelValue.type === 'BOOLEAN'" class="input" :value="modelValue.value" :aria-label="label" @change="changeValue"><option value="true">true</option><option value="false">false</option></select>
  <input v-else-if="modelValue.type !== 'NULL'" class="input" :value="modelValue.value" :aria-label="label" :placeholder="modelValue.type === 'STRING' ? '字符串（允许为空）' : '普通十进制数'" @input="changeValue">
  <span v-else class="sql-null">NULL</span>
</template>
