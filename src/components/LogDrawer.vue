<script setup>
defineProps({ title: { type: String, default: '操作日志与诊断' }, lines: { type: Array, default: () => [] } })
const expanded = defineModel('expanded', { default: true })
defineEmits(['clear'])
</script>

<template>
  <section class="detail-drawer" :class="{ collapsed: !expanded }" data-od-id="log-drawer">
    <header class="drawer-head"><strong>{{ title }}</strong><div class="button-row"><button class="btn btn-ghost" @click="$emit('clear')">清空</button><button class="btn btn-ghost" @click="expanded = !expanded">{{ expanded ? '收起日志' : '展开日志' }}</button></div></header>
    <div v-show="expanded" class="drawer-body"><pre class="terminal-box">{{ lines.length ? lines.join('\n') : '暂无日志' }}</pre></div>
  </section>
</template>
