import { createRouter, createWebHistory } from 'vue-router'
import ToolView from '@/views/ToolView.vue'
import { tools } from '@/config/tools'

const routes = [
  { path: '/', redirect: '/tools/remote' },
  ...tools.map((tool) => ({
    path: `/tools/${tool.id}`,
    name: tool.id,
    component: ToolView,
    meta: { tool }
  })),
  { path: '/:pathMatch(.*)*', redirect: '/tools/remote' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
