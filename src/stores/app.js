import { inject, reactive } from 'vue'

export const appStore = reactive({
  sidebarOpen: false,
  status: '远程外挂连接已就绪',
  statusType: 'idle',
  setStatus(message, type = 'idle') {
    this.status = message
    this.statusType = type
  }
})

export function useAppStore() {
  return inject('appStore', appStore)
}
