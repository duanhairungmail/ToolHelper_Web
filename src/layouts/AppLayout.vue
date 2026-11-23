<script setup>
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppStatusBar from '@/components/AppStatusBar.vue'
defineProps({ tool: { type: Object, required: true } })
</script>

<template><div class="app"><AppSidebar /><main class="workspace"><AppHeader :tool="tool" /><div class="screen-content"><slot /></div><AppStatusBar /></main></div></template>
