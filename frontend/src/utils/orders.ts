import type { Row } from '@/types/api'
import { formatDateTime } from '@/utils/datetime'

export const orderCategories: Record<string,string> = { DRUG: '用药', EXAMINATION: '检查', LAB: '检验', TREATMENT: '治疗', NURSING: '护理', DIET: '饮食', OTHER: '其他' }
export const nonDrugOrderCategories: Record<string,string> = Object.fromEntries(
  Object.entries(orderCategories).filter(([key]) => key !== 'DRUG'),
)
export function executionQuantity(row: Row): string {
  // The history API exposes the recorded unit as `unit`; do not infer it from the drug name.
  return `${row.quantity ?? '—'} ${row.unit || row.unit_snapshot || '单位未记录'}`
}
export function isSingleExecutionItem(item?: Row): boolean {
  return Boolean(item && ['EXAMINATION', 'LAB', 'TREATMENT'].includes(item.item_type) && item.unit === '次')
}
export interface OrderDraftLine {
  key: number; category: string; medicalItemId?: number; dosage?: number; doseUnit: string; quantity: number;
  route: string; instructions: string; orderType: string; startTime: string; endTime: string; groupKey: string;
  frequencyCode: string; firstDoseAt: string;
}
let key = 0
export function newOrderLine(category = 'DRUG'): OrderDraftLine {
  return { key: ++key, category, dosage: undefined, doseUnit: '', quantity: 1, route: '', instructions: '', orderType: 'TEMPORARY', startTime: '', endTime: '', groupKey: '', frequencyCode: category === 'DRUG' ? 'QD' : '', firstDoseAt: '' }
}
export function selectOrderItem(line: OrderDraftLine, item?: Row) {
  line.medicalItemId = item ? Number(item.id) : undefined
  line.dosage = undefined; line.quantity = 1; line.doseUnit = item?.dose_unit || ''; line.route = ''; line.groupKey = ''
  line.frequencyCode = item?.item_type === 'DRUG' ? 'QD' : ''; line.firstDoseAt = ''; line.endTime = ''
  if (item) line.category = item.item_type
}
export function frequencyName(row: Row): string {
  if (row.frequency_name || row.name) return String(row.frequency_name || row.name)
  if (row.frequency_code === 'ONCE') return '一次性'
  return row.frequency_code ? String(row.frequency_code) : '频次待维护'
}
export function frequencyIsOnce(row?: Row): boolean { return Boolean(row?.once_only || row?.code === 'ONCE' || row?.frequency_code === 'ONCE') }
export function medicationPlanWarning(line: OrderDraftLine, item?: Row, frequency?: Row): string {
  if (line.category !== 'DRUG' || !item || !(Number(line.quantity) > 0) || !(Number(line.dosage) > 0)) return ''
  const capacity = Number((line.quantity * Number(item.dose_units_per_stock)).toFixed(3))
  const remainder = Number((capacity - Number(line.dosage)).toFixed(3))
  if (remainder > 0) return `本次发药可覆盖至少一次给药，预计余量 ${remainder} ${line.doseUnit}；余量不自动增加给药次数。`
  return frequency ? `按${frequencyName(frequency)}执行；每次给药由实际执行流水记录。` : '每次给药由实际执行流水记录。'
}
export function administrationProgress(row: Row): string {
  if (row.item_type !== 'DRUG') return ''
  const next = row.next_administration_at ? `；下一次不早于 ${formatDateTime(row.next_administration_at)}` : ''
  return `已实际给药 ${Number(row.administration_count || 0)} 次；频次：${frequencyName(row)}${next}`
}
export function medicationRemainder(row: Row): string {
  if (row.item_type !== 'DRUG' || row.item_status !== 'COMPLETED') return ''
  const remaining = Number((Number(row.dispensed_quantity || 0) * Number(row.dose_units_per_stock || 0) - Number(row.administered_quantity || 0)).toFixed(3))
  return remaining > 0 ? `医嘱已结束，剩余 ${remaining} ${row.dose_unit || ''} 待核对；余药不通过继续给药消耗。` : ''
}
export function orderLineError(line: OrderDraftLine, item?: Row, frequency?: Row): string {
  if (!item) return '请选择项目'
  const quantity = isSingleExecutionItem(item) ? 1 : line.quantity
  if (!(quantity > 0) || !Number.isFinite(quantity)) return '请填写有效数量'
  if (!item.allow_fractional_quantity && !Number.isInteger(quantity)) return `数量须为整数（${item.unit}）`
  if (line.endTime && (!line.startTime || new Date(line.endTime) <= new Date(line.startTime))) return '请填写有效的开始、结束时间'
  if (line.category === 'DRUG') {
    if (!item.dose_unit || !Number(item.dose_units_per_stock)) return '请先维护该药的用量单位和换算资料'
    if (!(Number(line.dosage) > 0)) return '请填写单次用量'
    if (!line.frequencyCode) return '请选择给药频次'
    if (!String(item.allowed_routes || '').split(',').includes(line.route)) return '请选择适用的给药途径'
    if (Number((line.quantity * Number(item.dose_units_per_stock)).toFixed(3)) < Number(line.dosage)) return '本次发药数量不足一次给药用量'
    if (line.orderType === 'TEMPORARY' && !frequencyIsOnce(frequency) && !line.endTime) return '临时规律用药必须设置疗程结束时间'
    const start = line.startTime ? new Date(line.startTime) : undefined
    const first = line.firstDoseAt ? new Date(line.firstDoseAt) : start
    const end = line.endTime ? new Date(line.endTime) : undefined
    if (first && Number.isNaN(first.getTime())) return '首次给药时间格式不正确'
    if (start && first && first < start || end && first && first >= end) return '首次给药时间必须位于医嘱有效时间窗内'
    if (line.groupKey && !['静脉滴注', '静脉注射'].includes(line.route)) return '配液组仅用于注射途径'
  }
  return ''
}
export function orderLinePayload(line: OrderDraftLine, item?: Row) {
  return { medicalItemId: line.medicalItemId, dosage: line.category === 'DRUG' ? line.dosage : null,
    quantity: isSingleExecutionItem(item) ? 1 : line.quantity, doseUnit: line.category === 'DRUG' ? line.doseUnit : null,
    route: line.category === 'DRUG' ? line.route : null, instructions: line.instructions,
    orderType: line.orderType, startTime: line.startTime ? new Date(line.startTime).toISOString() : null,
    endTime: line.endTime ? new Date(line.endTime).toISOString() : null, groupKey: line.groupKey || null,
    frequencyCode: line.category === 'DRUG' ? line.frequencyCode : null,
    firstDoseAt: line.category === 'DRUG' && line.firstDoseAt ? new Date(line.firstDoseAt).toISOString() : null }
}
