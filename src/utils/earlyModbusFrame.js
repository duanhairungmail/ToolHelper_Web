// RTU CRC16，线上顺序低字节在前；只用于预览，实际应答由 Java 独立编码。
export function rtuFrameHex(body) {
  let crc = 0xffff
  for (const byte of body) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = crc & 1 ? (crc >>> 1) ^ 0xa001 : crc >>> 1
  }
  return [...body, crc & 255, crc >>> 8].map(v => v.toString(16).padStart(2, '0').toUpperCase()).join(' ')
}
