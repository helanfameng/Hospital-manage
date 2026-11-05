import { describe, expect, it } from 'vitest'
import { executionQuantity, isSingleExecutionItem, medicationPlanWarning, medicationRemainder, newOrderLine, nonDrugOrderCategories, orderLineError, orderLinePayload, suggestedDispenseQuantity } from './orders'

describe('医嘱数量和给药规则', () => {
  const drug = { item_type: 'DRUG', unit: '盒', dose_unit: '粒', dose_units_per_stock: 24, allowed_routes: '口服' }
  it('临时药只按单次剂量计算建议发药量', () => {
    const line = { ...newOrderLine(), dosePerAdministration: 2, doseUnit: '粒', route: '口服' }
    expect(suggestedDispenseQuantity(line,drug)).toBe(1)
    line.requestedDispenseQuantity = 1
    expect(orderLineError(line,drug)).toBe('')
    expect(medicationPlanWarning(line,drug)).toContain('剩余 22 粒')
    expect(orderLinePayload(line,drug)).toMatchObject({ dosePerAdministration: 2, requestedDispenseQuantity: 1, administrationMode: 'ONCE' })
  })
  it('可拆分药品的建议数量向上保留三位', () => {
    const line = { ...newOrderLine(), dosePerAdministration: 1, doseUnit: 'mL', route: '静脉滴注' }
    expect(suggestedDispenseQuantity(line,{ item_type:'DRUG',unit:'瓶',dose_unit:'mL',dose_units_per_stock:3,allowed_routes:'静脉滴注',allow_fractional_quantity:true })).toBe(0.334)
  })
  it('长期定时和按需给药必须提供约束', () => {
    const base = { ...newOrderLine(), orderType:'LONG_TERM', administrationMode:'SCHEDULED', dosePerAdministration:2, requestedDispenseQuantity:1, doseUnit:'粒', route:'口服' }
    expect(orderLineError(base,drug)).toContain('间隔')
    expect(orderLineError({ ...base, administrationIntervalMinutes:480 },drug)).toBe('')
    expect(orderLineError({ ...base, administrationMode:'PRN', administrationIntervalMinutes:360 },drug)).toContain('每日最大')
    expect(orderLineError({ ...base, administrationMode:'PRN', administrationIntervalMinutes:360, maxAdministrationsPerDay:3 },drug)).toBe('')
  })
  it.each(['EXAMINATION','LAB','TREATMENT'])('%s按次项目固定一次', item_type => {
    const line = newOrderLine(item_type); line.executionQuantityPerOperation = 5
    const item = { item_type, unit:'次' }
    expect(isSingleExecutionItem(item)).toBe(true)
    expect(orderLinePayload(line,item).executionQuantityPerOperation).toBe(1)
  })
  it('完成后的余药按发药和给药实际量计算', () => {
    expect(medicationRemainder({ item_type:'DRUG',item_status:'COMPLETED',dispensed_quantity:1,dose_units_per_stock:24,executed_quantity:2,dose_unit:'粒' })).toContain('22 粒')
  })
})

describe('执行历史数量单位', () => {
  it.each([
    [{ quantity:500,unit:'mL' },'500 mL'], [{ quantity:2,unit:'袋' },'2 袋'],
    [{ quantity:1,unit_snapshot:'次' },'1 次'], [{ quantity:1 },'1 单位未记录'],
  ])('按事实记录展示：%j', (row, expected) => expect(executionQuantity(row)).toBe(expected))
})

it('非药品筛选不包含药品', () => {
  expect(nonDrugOrderCategories).not.toHaveProperty('DRUG')
  expect(Object.keys(nonDrugOrderCategories)).toEqual(['EXAMINATION','LAB','TREATMENT','NURSING','DIET','OTHER'])
})
