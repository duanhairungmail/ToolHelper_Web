import { rtuFrameHex } from './earlyModbusFrame.js'

export const METER_ENCODING = 'firesys-legacy-v1'
export const METER_DOWN = 'firetopic/__meterTopicKey/down'
export const METER_UP = 'firetopic/__meterTopicKey/up'
const definitions = {
  dc: {
    name: '直流电表', model: 'DJSU2108', address: 1,
    query: '01 03 00 00 00 11 85 C6',
    fields: [
      { key: 'voltage', label: '电压', address: '0x0000', unit: 'V', step: 0.1, value: 350 },
      { key: 'current', label: '电流', address: '0x0001', unit: 'A', step: 0.001, value: 10 },
      { key: 'power', label: '有功功率', address: '0x0003', unit: 'kW', step: 0.0001, value: 3.5 },
      { key: 'totalEnergy', label: '组合有功总电能', address: '0x0005', unit: 'kWh', step: 0.01, value: 1250 },
      { key: 'sharpEnergy', label: '尖电能', address: '0x0007', unit: 'kWh', step: 0.01, value: 100 },
      { key: 'peakEnergy', label: '峰电能', address: '0x0009', unit: 'kWh', step: 0.01, value: 350 },
      { key: 'flatEnergy', label: '平电能', address: '0x000B', unit: 'kWh', step: 0.01, value: 500 },
      { key: 'valleyEnergy', label: '谷电能', address: '0x000D', unit: 'kWh', step: 0.01, value: 300 }
    ]
  },
  ac: {
    name: '单相交流电表', model: 'DDSU2108', address: 2,
    query: '02 03 00 00 00 0E C4 3D',
    fields: [
      { key: 'totalEnergy', label: '组合有功总电能', address: '0x0000', unit: 'kWh', step: 0.01, value: 1250 },
      { key: 'forwardEnergy', label: '正向有功总电能', address: '0x0002', unit: 'kWh', step: 0.01, value: 1200 },
      { key: 'reverseEnergy', label: '反向有功总电能', address: '0x0004', unit: 'kWh', step: 0.01, value: 50 },
      { key: 'voltage', label: '电压', address: '0x0006', unit: 'V', step: 0.1, value: 220 },
      { key: 'current', label: '电流', address: '0x0007', unit: 'A', step: 0.001, value: 5 },
      { key: 'power', label: '有功功率', address: '0x0009', unit: 'kW', step: 0.0001, value: 1.045 },
      { key: 'powerFactor', label: '功率因数', address: '0x000B', unit: '', step: 0.001, value: 0.95 },
      { key: 'frequency', label: '频率', address: '0x000C', unit: 'Hz', step: 0.01, value: 50 }
    ]
  }
}

export function createMeterDrafts() {
  return Object.fromEntries(Object.entries(definitions).map(([type, profile]) => [type, {
    ...profile, id: `meter-${type}`, encodingProfile: METER_ENCODING, baudRateRaw: type === 'dc' ? '0' : undefined,
    fields: profile.fields.map(field => ({ ...field, value: String(field.value) }))
  }]))
}

// 使用十进制字符串和 BigInt 转换，避免浮点乘倍率后误差或静默四舍五入。
export function meterRegisters(type, draft) {
  if (!definitions[type] || draft.encodingProfile !== METER_ENCODING) throw new Error('电表类型或兼容编码无效')
  const registers = Array(type === 'dc' ? 17 : 14).fill(0)
  for (const field of draft.fields) {
    const value = String(field.value), scale = String(field.step).split('.')[1]?.length || 0
    if (value.length > 40 || !/^-?\d+(?:\.\d+)?$/.test(value)) throw new Error(`${field.label} 必须为十进制数值`)
    const negative = value.startsWith('-'), [whole, fraction = ''] = value.replace(/^-/, '').split('.')
    if (/[1-9]/.test(fraction.slice(scale))) throw new Error(`${field.label} 超出允许精度`)
    const magnitude = BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.slice(0, scale).padEnd(scale, '0') || '0')
    const raw = Number(negative ? -magnitude : magnitude)
    const single = ['voltage', 'frequency', 'powerFactor'].includes(field.key)
    const signed = type === 'ac' && ['totalEnergy', 'power', 'powerFactor'].includes(field.key)
    const min = signed ? single ? -1000 : -8388608 : 0
    const max = single ? signed ? 1000 : 32767 : 8388607
    if (!Number.isSafeInteger(raw) || raw < min || raw > max) throw new Error(`${field.label} 超出兼容编码范围`)
    const start = Number(field.address)
    if (single) registers[start] = raw & 65535
    else { registers[start] = Math.floor(raw / 256) & 65535; registers[start + 1] = ((raw % 256) + 256) % 256 }
  }
  if (type === 'dc') {
    if (!/^\d+$/.test(String(draft.baudRateRaw)) || Number(draft.baudRateRaw) > 65535) throw new Error('波特率原始字必须为 0–65535 整数')
    registers[15] = 1; registers[16] = Number(draft.baudRateRaw)
  }
  return registers
}
// 与 BigDecimal.toPlainString 对齐，应用后不会因前导零/负零误报草稿未保存。
export function normalizeMeterDecimal(value) {
  const negative = value.startsWith('-'), [whole, fraction] = value.replace(/^-/, '').split('.')
  const unsigned = whole.replace(/^0+(?=\d)/, '') + (fraction === undefined ? '' : `.${fraction}`)
  return negative && /[1-9]/.test(unsigned) ? `-${unsigned}` : unsigned
}
export function meterPayload(type, draft, gatewayKey) {
  meterRegisters(type, draft)
  return { id: draft.id, gatewayKey, type, address: definitions[type].address, encodingProfile: METER_ENCODING,
    ...(type === 'dc' ? { baudRateRaw: Number(draft.baudRateRaw) } : {}),
    measurements: Object.fromEntries(draft.fields.map(field => [field.key, normalizeMeterDecimal(String(field.value))])) }
}
export function meterResponse(type, draft) {
  const registers = meterRegisters(type, draft)
  return rtuFrameHex([definitions[type].address, 3, registers.length * 2, ...registers.flatMap(raw => [raw >>> 8, raw & 255])])
}
