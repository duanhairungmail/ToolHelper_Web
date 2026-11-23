<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import ToolContent from '@/components/ToolContent.vue'
const route = useRoute()
const tool = computed(() => route.meta.tool)
</script>

<template><AppLayout :tool="tool"><ToolContent :tool-id="tool.id" /></AppLayout></template>
