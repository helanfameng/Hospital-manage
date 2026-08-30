import { describe, expect, it } from 'vitest'
import { administrationProgress, executionQuantity, frequencyName, isSingleExecutionItem, medicationPlanWarning, medicationRemainder, newOrderLine, nonDrugOrderCategories, orderLineError, orderLinePayload } from './orders'

describe('非药品执行类别', () => {
  it('不提供药品筛选', () => {
    expect(nonDrugOrderCategories).not.toHaveProperty('DRUG')
    expect(Object.keys(nonDrugOrderCategories)).toEqual(['EXAMINATION', 'LAB', 'TREATMENT', 'NURSING', 'DIET', 'OTHER'])
  })
})

describe('药品给药计划', () => {
  const item = { item_type: 'DRUG', unit: '袋', dose_unit: 'mL', dose_units_per_stock: 500, allowed_routes: '静脉滴注' }
  const q4h = { code: 'Q4H', frequency_name: '每4小时1次', interval_minutes: 240, once_only: false }
  const once = { code: 'ONCE', frequency_name: '一次', interval_minutes: 0, once_only: true }
  const plan = () => ({ ...newOrderLine(), dosage: 500, doseUnit: 'mL', quantity: 2, route: '静脉滴注', frequencyCode: 'Q4H', startTime: '2026-08-29T08:00:00', firstDoseAt: '2026-08-29T08:00:00', endTime: '2026-08-30T08:00:00' })
  it('按频次、首次给药时间和疗程结束时间校验规律临时用药', () => {
    const line = plan()
    expect(orderLineError(line, item, q4h)).toBe('')
    line.endTime = ''
    expect(orderLineError(line, item, q4h)).toContain('疗程结束时间')
    line.endTime = '2026-08-30T08:00:00'; line.firstDoseAt = '2026-08-30T08:00:00'
    expect(orderLineError(line, item, q4h)).toContain('有效时间窗')
  })
  it('一次性频次不需要人为填写总次数', () => {
    const line = { ...plan(), frequencyCode: 'ONCE', firstDoseAt: '', endTime: '' }
    expect(orderLineError(line, item, once)).toBe('')
    expect(orderLinePayload(line, item)).toMatchObject({ frequencyCode: 'ONCE', firstDoseAt: null, quantity: 2, dosage: 500 })
    expect(medicationPlanWarning(line, item, once)).toContain('一次')
  })
  it('发药数量只需覆盖一次用量，余量不转化为给药次数', () => {
    const line = { ...plan(), quantity: 1 }
    expect(orderLineError(line, item, q4h)).toBe('')
    line.quantity = 0
    expect(orderLineError(line, item, q4h)).toContain('有效数量')
    expect(medicationPlanWarning({ ...plan(), quantity: 2 }, item, q4h)).toContain('预计余量 500 mL')
  })
  it('长期医嘱携带频次但不携带临时疗程结束要求，非药品不携带给药计划', () => {
    const line = { ...plan(), orderType: 'LONG_TERM', endTime: '' }
    expect(orderLineError(line, item, q4h)).toBe('')
    expect(orderLinePayload(line, item).frequencyCode).toBe('Q4H')
    const nonDrug = newOrderLine('LAB')
    expect(orderLinePayload(nonDrug, { item_type: 'LAB', unit: '次' })).toMatchObject({ frequencyCode: null, firstDoseAt: null })
  })
  it('展示实际给药次数和下一次可执行时间', () => {
    const row = { item_type: 'DRUG', frequency_code: 'Q4H', frequency_name: '每4小时1次', administration_count: 1, next_administration_at: '2026-08-29T12:00:00', item_status: 'IN_PROGRESS' }
    expect(administrationProgress(row)).toContain('已实际给药 1 次')
    expect(administrationProgress(row)).toContain('下一次不早于')
    expect(medicationRemainder({ ...row, item_status: 'COMPLETED', dispensed_quantity: 3, dose_units_per_stock: 500, administered_quantity: 1000, dose_unit: 'mL' })).toContain('剩余 500 mL')
    expect(medicationRemainder(row)).toBe('')
  })
  it('显示频次名称', () => expect(frequencyName({ frequency_code: 'ONCE' })).toBe('一次性'))
})

describe('执行历史数量单位', () => {
  it.each([
    [{ phase: 'ADMINISTER', quantity: 500, unit: 'mL' }, '500 mL'],
    [{ phase: 'DISPENSE', quantity: 2, unit: '袋' }, '2 袋'],
    [{ phase: 'ADMINISTER', quantity: 3, unit: '粒' }, '3 粒'],
    [{ phase: 'PERFORM', quantity: 1, unit: '次' }, '1 次'],
    [{ status: 'NOT_DONE', quantity: 0, unit: '次' }, '0 次'],
    [{ quantity: 500, unit_snapshot: 'mL' }, '500 mL'],
    [{ quantity: 1 }, '1 单位未记录'],
  ])('按执行记录展示数量及实际单位：%j', (row, expected) => {
    expect(executionQuantity(row)).toBe(expected)
  })
})

describe('按次项目数量规则', () => {
  it.each(['EXAMINATION','LAB','TREATMENT'])('%s不沿用草稿残留的多次数量', item_type => {
    const line = newOrderLine(item_type); line.quantity = 5
    const item = { item_type, unit: '次' }
    expect(isSingleExecutionItem(item)).toBe(true)
    expect(orderLinePayload(line,item).quantity).toBe(1)
  })
  it('药品发药数量不被覆盖', () => {
    const line = newOrderLine(); line.quantity = 2
    expect(orderLinePayload(line,{ item_type: 'DRUG', unit: '盒' }).quantity).toBe(2)
  })
})
