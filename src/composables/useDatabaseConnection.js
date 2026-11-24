import { ref, watch } from 'vue'

export function useDatabaseConnection(dispatch) {
  const type = ref('MYSQL')
  const host = ref('')
  const port = ref(3306)
  const username = ref('')
  const database = ref('')
  const connectionName = ref('')
  const ssl = ref(false)
  const transferPassword = ref(false)
  const password = ref('')
  const formError = ref('')

  function clearPassword() { password.value = ''; transferPassword.value = false }
  watch(type, () => { port.value = type.value === 'MYSQL' ? 3306 : 5432; clearPassword() }, { flush: 'sync' })
  watch(transferPassword, (enabled) => { if (!enabled) password.value = '' }, { flush: 'sync' })

  async function connect() {
    formError.value = ''
    if (!host.value.trim() || !username.value.trim() || !Number.isInteger(Number(port.value))
        || Number(port.value) < 1 || Number(port.value) > 65535) {
      formError.value = '请填写主机、1～65535 的整数端口和用户名。'
      return
    }
    const body = {
      type: type.value, host: host.value.trim(), port: Number(port.value), username: username.value,
      database: database.value, connectionName: connectionName.value, ssl: ssl.value,
      ...(transferPassword.value ? { password: password.value } : {})
    }
    // 发起后立即清空表单；不会在失败、刷新或切换页面后重放密码。
    clearPassword()
    try { await dispatch('connect', body) }
    finally { delete body.password }
  }

  return { type, host, port, username, database, connectionName, ssl, transferPassword, password, formError, connect, clearPassword }
}
