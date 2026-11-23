export function escapeSql(value) {
  return String(value).replaceAll("'", "''")
}

export function currentTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}
