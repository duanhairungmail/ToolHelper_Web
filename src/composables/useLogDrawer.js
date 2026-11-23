import { ref } from 'vue'

export function useLogDrawer(initialLines = []) {
  const expanded = ref(true)
  const lines = ref([...initialLines])
  const append = (message) => lines.value.push(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${message}`)
  const clear = () => { lines.value = [] }
  return { expanded, lines, append, clear }
}
