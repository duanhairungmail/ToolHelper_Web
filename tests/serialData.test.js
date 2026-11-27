import test from 'node:test'
import assert from 'node:assert/strict'
import { checksumBytes, formatHex, toSerialBytes } from '../src/utils/serialData.js'

test('HEX校验拒绝奇数位、非法字符和隐式CRLF', () => {
  assert.throws(() => toSerialBytes({ mode: 'HEX', data: 'A' }), /奇数/)
  assert.throws(() => toSerialBytes({ mode: 'HEX', data: '01 ZZ' }), /十六进制/)
  assert.throws(() => toSerialBytes({ mode: 'HEX', data: '01', appendCrlf: true }), /显式输入/)
})

test('文本先UTF-8及CRLF再追加所选校验', () => {
  assert.equal(formatHex(toSerialBytes({ mode: 'TEXT', data: 'A', appendCrlf: true })), '41 0D 0A')
  assert.equal(formatHex(toSerialBytes({ mode: 'TEXT', data: 'A', appendCrlf: true, checksum: 'XOR' })), '41 0D 0A 46')
})

test('五种校验算法使用旧工具定义的初值、多项式及字节序', () => {
  const sample = new TextEncoder().encode('123456789')
  assert.equal(formatHex(checksumBytes(sample, 'MODBUS_CRC16')), '37 4B')
  assert.equal(formatHex(checksumBytes(sample, 'CRC16_CCITT')), '29 B1')
  assert.equal(formatHex(checksumBytes(sample, 'CRC32')), '26 39 F4 CB')
  assert.equal(formatHex(checksumBytes(new Uint8Array([0x01, 0x03, 0x00, 0x00, 0x00, 0x0a]), 'LRC')), 'F2')
  assert.equal(formatHex(checksumBytes(new Uint8Array([0x01, 0x03, 0x00, 0x00, 0x00, 0x0a]), 'XOR')), '08')
})
