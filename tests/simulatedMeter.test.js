import test from 'node:test'
import assert from 'node:assert/strict'
import { createMeterDrafts, meterRegisters, meterPayload, meterResponse } from '../src/utils/simulatedMeter.js'

test('电表预览与独立固定响应向量一致', () => {
  const drafts = createMeterDrafts()
  assert.equal(meterResponse('dc', drafts.dc), '01 03 22 0D AC 00 27 00 10 00 88 00 B8 01 E8 00 48 00 27 00 10 00 88 00 B8 00 C3 00 50 00 75 00 30 00 01 00 00 F7 AC')
  assert.equal(meterResponse('ac', drafts.ac), '02 03 1C 01 E8 00 48 01 D4 00 C0 00 13 00 88 08 98 00 13 00 88 00 28 00 D2 03 B6 13 88 00 00 3E 3B')
  assert.equal(meterPayload('dc', drafts.dc, 'g').measurements.current, '10')
})
test('电表十进制精度、负数和旧算法边界校验', () => {
  const ac = createMeterDrafts().ac
  const power = ac.fields.find(f => f.key === 'power')
  power.value = '-1.0450'
  assert.deepEqual(meterRegisters('ac', ac).slice(9, 11), [0xffd7, 0x2e])
  power.value = '1.23000'
  assert.doesNotThrow(() => meterRegisters('ac', ac))
  for (const value of ['1.00001', '-838.8609', '838.8608', 'NaN', '', '1e2']) {
    power.value = value
    assert.throws(() => meterRegisters('ac', ac))
  }
  const dc = createMeterDrafts().dc
  dc.baudRateRaw = '65536'
  assert.throws(() => meterRegisters('dc', dc))
})


test('提交规范化前导零和负零，不改变精确十进制值', () => {
  const draft = createMeterDrafts().ac
  draft.fields.find(f => f.key === 'power').value = '-000.0000'
  draft.fields.find(f => f.key === 'voltage').value = '00220.0'
  const payload = meterPayload('ac', draft, 'g')
  assert.equal(payload.measurements.power, '0.0000')
  assert.equal(payload.measurements.voltage, '220.0')
})
