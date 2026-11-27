import { createRouter, createWebHistory } from 'vue-router'
import ToolView from '@/views/ToolView.vue'
import AesDemoView from '@/views/AesDemoView.vue'
import { tools } from '@/config/tools'

const routes = [
  { path: '/', redirect: '/tools/remote' },
  { path: '/demo/aes', name: 'aes-demo', component: AesDemoView, meta: { tool: { id: 'aes-demo', group: '调试工具', name: 'AES 加密/解密 · 界面预览', keywords: '', lead: '用于评审界面布局；此页面不执行加解密。', action: '' } } },
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
