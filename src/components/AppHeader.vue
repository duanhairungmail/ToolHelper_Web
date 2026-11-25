<script setup>
import { useAppStore } from '@/stores/app'
defineProps({ tool: { type: Object, required: true } })
const store = useAppStore()
</script>

<template>
  <header class="page-head" data-od-id="page-header">
    <div class="title-row"><button class="btn btn-secondary mobile-menu" @click="store.sidebarOpen = true">菜单</button><div><h1>{{ tool.name }}</h1><p>{{ tool.lead }}</p></div></div>
    <button v-if="!['api', 'mac', 'druid', 'kylin'].includes(tool.id)" class="btn btn-secondary" @click="store.setStatus(`${tool.action}操作已完成`, 'success')">{{ tool.action }}</button>
  </header>
</template>
