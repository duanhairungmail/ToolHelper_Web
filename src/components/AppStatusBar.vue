<script setup>
import { useAppStore } from '@/stores/app'
const store = useAppStore()
</script>

<template><footer class="statusbar"><div class="status" :class="store.statusType"><span class="status-dot"></span><span>{{ store.status }}</span></div><span class="shortcut">Ctrl + K 搜索</span></footer></template>
