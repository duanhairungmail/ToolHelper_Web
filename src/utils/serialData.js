const hexPattern = /^[\da-f]+$/i

export function toSerialBytes({ mode, data, appendCrlf = false, checksum = 'NONE' }) {
  let bytes
  if (mode === 'HEX') {
    if (appendCrlf) throw new Error('HEX模式不支持自动追加CRLF，请显式输入0D 0A')
    const compact = data.replace(/[\s-]/g, '')
    if (!compact || !hexPattern.test(compact)) throw new Error('HEX仅允许十六进制字符、空白和短横线')
    if (compact.length % 2) throw new Error('HEX位数为奇数，请补齐完整字节后再发送')
    bytes = Uint8Array.from(compact.match(/../g), pair => Number.parseInt(pair, 16))
  } else {
    bytes = new TextEncoder().encode(data)
    if (appendCrlf) bytes = Uint8Array.from([...bytes, 0x0d, 0x0a])
  }
  if (!bytes.length) throw new Error('发送内容不能为空')
  if (bytes.length > 4096) throw new Error('单次发送不能超过4096字节')
  return Uint8Array.from([...bytes, ...checksumBytes(bytes, checksum)])
}

export function checksumBytes(bytes, algorithm = 'NONE') {
  switch (algorithm) {
    case 'NONE': return []
    case 'MODBUS_CRC16': {
      let crc = 0xffff
      for (const byte of bytes) {
        crc ^= byte
        for (let i = 0; i < 8; i++) crc = crc & 1 ? (crc >>> 1) ^ 0xa001 : crc >>> 1
      }
      return [crc & 0xff, crc >>> 8]
    }
    case 'CRC16_CCITT': {
      let crc = 0xffff
      for (const byte of bytes) {
        crc ^= byte << 8
        for (let i = 0; i < 8; i++) crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff
      }
      return [crc >>> 8, crc & 0xff]
    }
    case 'CRC32': {
      let crc = 0xffffffff
      for (const byte of bytes) {
        crc ^= byte
        for (let i = 0; i < 8; i++) crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1
      }
      crc = (crc ^ 0xffffffff) >>> 0
      return [crc & 0xff, (crc >>> 8) & 0xff, (crc >>> 16) & 0xff, crc >>> 24]
    }
    case 'LRC': return [(-bytes.reduce((sum, byte) => (sum + byte) & 0xff, 0)) & 0xff]
    case 'XOR': return [bytes.reduce((sum, byte) => sum ^ byte, 0)]
    default: throw new Error('不支持的校验算法')
  }
}

export function formatHex(bytes) {
  return [...bytes].map(byte => byte.toString(16).padStart(2, '0').toUpperCase()).join(' ')
}

export function bytesFromHex(hex) {
  const clean = hex.replace(/\s/g, '')
  return Uint8Array.from(clean.match(/../g) || [], pair => Number.parseInt(pair, 16))
}
