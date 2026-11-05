import type { Row } from '@/types/api'

export const orderCategories: Record<string,string> = { DRUG: '用药', EXAMINATION: '检查', LAB: '检验', TREATMENT: '治疗', NURSING: '护理', DIET: '饮食', OTHER: '其他' }
export const nonDrugOrderCategories: Record<string,string> = Object.fromEntries(Object.entries(orderCategories).filter(([key]) => key !== 'DRUG'))
export const medicationRoutes = ['口服', '静脉滴注', '静脉注射', '肌内注射', '皮下注射', '外用', '吸入', '滴眼', '滴耳', '鼻用', '其他']
export const administrationModes: Record<string,string> = { SCHEDULED: '定时给药', PRN: '按需给药' }

export function executionQuantity(row: Row): string {
  return `${row.quantity ?? '—'} ${row.unit || row.unit_snapshot || '单位未记录'}`
}
export function isSingleExecutionItem(item?: Row): boolean {
  return Boolean(item && ['EXAMINATION', 'LAB', 'TREATMENT'].includes(item.item_type) && item.unit === '次')
}

export interface OrderDraftLine {
  key: number; category: string; medicalItemId?: number;
  dosePerAdministration?: number; requestedDispenseQuantity?: number; executionQuantityPerOperation: number;
  doseUnit: string; route: string; instructions: string; orderType: string;
  administrationMode: string; administrationIntervalMinutes?: number; maxAdministrationsPerDay?: number;
  startTime: string; endTime: string; groupKey: string;
}
let key = 0
export function newOrderLine(category = 'DRUG'): OrderDraftLine {
  return { key: ++key, category, dosePerAdministration: undefined, requestedDispenseQuantity: undefined,
    executionQuantityPerOperation: 1, doseUnit: '', route: '', instructions: '', orderType: 'TEMPORARY',
    administrationMode: 'ONCE', administrationIntervalMinutes: undefined, maxAdministrationsPerDay: undefined,
    startTime: '', endTime: '', groupKey: '' }
}
export function selectOrderItem(line: OrderDraftLine, item?: Row) {
  line.medicalItemId = item ? Number(item.id) : undefined
  line.dosePerAdministration = undefined; line.requestedDispenseQuantity = undefined; line.executionQuantityPerOperation = 1
  line.doseUnit = item?.dose_unit || ''; line.route = ''; line.groupKey = ''
  if (item) line.category = item.item_type
}
export function suggestedDispenseQuantity(line: OrderDraftLine, item?: Row): number | undefined {
  if (line.category !== 'DRUG' || !item) return
  const dose = Math.round(Number(line.dosePerAdministration) * 1000), factor = Math.round(Number(item.dose_units_per_stock) * 1000)
  if (!Number.isSafeInteger(dose) || dose <= 0 || !Number.isSafeInteger(factor) || factor <= 0) return
  const scale = item.allow_fractional_quantity ? 1000n : 1n
  return Number((BigInt(dose) * scale + BigInt(factor) - 1n) / BigInt(factor)) / Number(scale)
}
export function medicationPlanWarning(line: OrderDraftLine, item?: Row): string {
  const suggested = suggestedDispenseQuantity(line,item)
  if (suggested === undefined || line.requestedDispenseQuantity == null) return ''
  if (line.requestedDispenseQuantity !== suggested) return `建议发药 ${suggested} ${item?.unit}；实际发药时由药师确认，存在差异必须填写原因。`
  const remainder = Number((line.requestedDispenseQuantity * Number(item?.dose_units_per_stock) - Number(line.dosePerAdministration)).toFixed(3))
  return remainder > 0 ? `一次给药后预计剩余 ${remainder} ${line.doseUnit}，医嘱结束时需要登记退回、留存或废弃。` : ''
}
export function medicationRemainder(row: Row): string {
  if (row.item_type !== 'DRUG' || row.item_status !== 'COMPLETED') return ''
  const remaining = Number((Number(row.dispensed_quantity || 0) * Number(row.dose_units_per_stock || 0) - Number(row.executed_quantity || 0)).toFixed(3))
  return remaining > 0 ? `剩余 ${remaining} ${row.dose_unit || ''} 待处置。` : ''
}
export function orderLineError(line: OrderDraftLine, item?: Row): string {
  if (!item) return '请选择项目'
  if (line.endTime && (!line.startTime || new Date(line.endTime) <= new Date(line.startTime))) return '请填写有效的开始、结束时间'
  if (line.category === 'DRUG') {
    if (!item.dose_unit || !Number(item.dose_units_per_stock)) return '请先维护该药的用量单位和换算资料'
    if (!(Number(line.dosePerAdministration) > 0)) return '请填写单次用量'
    if (!String(item.allowed_routes || '').split(',').includes(line.route)) return '请选择适用的给药途径'
    if (line.orderType === 'LONG_TERM') {
      if (!['SCHEDULED','PRN'].includes(line.administrationMode)) return '请选择长期给药方式'
      if (!Number.isInteger(line.administrationIntervalMinutes) || Number(line.administrationIntervalMinutes) < 1) return '请填写有效给药间隔'
      if (line.administrationMode === 'PRN' && (!Number.isInteger(line.maxAdministrationsPerDay) || Number(line.maxAdministrationsPerDay) < 1)) return '请填写按需给药每日最大次数'
    }
    const requested = line.requestedDispenseQuantity ?? suggestedDispenseQuantity(line,item)
    if (!(Number(requested) > 0)) return '请填写有效的建议发药数量'
    if (!item.allow_fractional_quantity && !Number.isInteger(requested)) return `建议发药数量须为整数（${item.unit}）`
  } else {
    const quantity = isSingleExecutionItem(item) ? 1 : line.executionQuantityPerOperation
    if (!(quantity > 0) || !Number.isFinite(quantity)) return '请填写有效执行数量'
    if (!item.allow_fractional_quantity && !Number.isInteger(quantity)) return `数量须为整数（${item.unit}）`
  }
  return ''
}
export function orderLinePayload(line: OrderDraftLine, item?: Row) {
  const drug=line.category === 'DRUG'
  return { medicalItemId: line.medicalItemId,
    dosePerAdministration: drug ? line.dosePerAdministration : null,
    requestedDispenseQuantity: drug ? (line.requestedDispenseQuantity ?? suggestedDispenseQuantity(line,item)) : null,
    executionQuantityPerOperation: drug ? null : (isSingleExecutionItem(item) ? 1 : line.executionQuantityPerOperation),
    doseUnit: drug ? line.doseUnit : null, route: drug ? line.route : null, instructions: line.instructions,
    orderType: line.orderType, administrationMode: drug ? (line.orderType === 'TEMPORARY' ? 'ONCE' : line.administrationMode) : null,
    administrationIntervalMinutes: drug && line.orderType === 'LONG_TERM' ? line.administrationIntervalMinutes : null,
    maxAdministrationsPerDay: drug && line.orderType === 'LONG_TERM' && line.administrationMode === 'PRN' ? line.maxAdministrationsPerDay : null,
    startTime: line.startTime ? new Date(line.startTime).toISOString() : null,
    endTime: line.endTime ? new Date(line.endTime).toISOString() : null }
}
