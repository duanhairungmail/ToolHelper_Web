<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import AesDemo from '@/components/AesDemo.vue'

const route = useRoute()
const tool = computed(() => route.meta.tool)
</script>

<template><AppLayout :tool="tool"><AesDemo /></AppLayout></template>
