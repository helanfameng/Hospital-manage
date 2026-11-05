export type LabelMap = Record<string, string>

export const admissionStatusLabels: LabelMap = {
  PENDING: '待入院',
  IN_HOSPITAL: '住院中',
  PENDING_DISCHARGE: '待出院',
  DISCHARGED: '已出院',
  CANCELLED: '已取消',
}

export const orderTypeLabels: LabelMap = {
  TEMPORARY: '临时医嘱',
  LONG_TERM: '长期医嘱',
}

export const orderStatusLabels: LabelMap = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  IN_PROGRESS: '执行中',
  COMPLETED: '已完成',
  STOPPED: '已停止',
  CANCELLED: '已撤销',
}

export const orderStatusOptions = Object.entries(orderStatusLabels).map(([value, label]) => ({ value, label }))
export const orderStatusTagTypes: Record<string, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info', SUBMITTED: 'primary', IN_PROGRESS: 'primary',
  COMPLETED: 'success', STOPPED: 'warning', CANCELLED: 'danger',
}

export const medicalItemTypeLabels: LabelMap = {
  DRUG: '药品',
  EXAMINATION: '检查',
  LAB: '检验',
  NURSING: '护理',
  DIET: '饮食',
  OTHER: '其他',
  TREATMENT: '治疗',
  BED_FEE: '床位费',
}

export const depositTypeLabels: LabelMap = {
  INITIAL: '初始押金',
  SUPPLEMENT: '补交押金',
  REFUND: '退款',
  REVERSAL: '冲正',
}

export const paymentMethodLabels: LabelMap = {
  CASH: '现金',
  CARD: '银行卡',
  TRANSFER: '转账',
}

export const alertLevelLabels: LabelMap = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '严重',
}

export const staffTypeLabels: LabelMap = {
  ADMIN: '系统管理员',
  ADMISSION: '住院处人员',
  DOCTOR: '医生',
  NURSE: '护士',
  PHARMACY: '药房人员',
  CASHIER: '收费人员',
}

export const roleLabels: LabelMap = { ...staffTypeLabels }

export const permissionLabels: LabelMap = {
  ADMIN_MANAGE: '系统管理',
  PATIENT_READ: '患者查询',
  PATIENT_WRITE: '患者维护',
  ADMISSION_MANAGE: '入院与床位管理',
  ORDER_WRITE: '医嘱开立',
  ORDER_EXECUTE: '医嘱执行',
  PHARMACY_EXECUTE: '药品发退',
  MEDICATION_ADMINISTER: '住院给药记录',
  NURSING_RECORD: '护理观察记录',
  RESULT_RECORD: '住院检查检验结果',
  DIAGNOSIS_RECORD: '住院诊断记录',
  CLINICAL_RECORD: '住院临床记录',
  DEPOSIT_MANAGE: '押金管理',
  SETTLEMENT_MANAGE: '出院结算',
  STATISTICS_READ: '统计查询',
  AUDIT_READ: '审计查询',
}

export const auditActionLabels: LabelMap = {
  USER_CREATE: '创建用户',
  USER_STATUS: '调整用户状态',
  PASSWORD_CHANGE: '修改密码',
  PATIENT_CREATE: '新建患者',
  PATIENT_UPDATE: '更新患者',
  ADMISSION_CREATE: '办理入院',
  BED_TRANSFER: '办理转床',
  BED_STATUS: '调整床位状态',
  DISCHARGE_REQUEST: '申请出院',
  DISCHARGE_WITHDRAW: '撤回出院申请',
  REQUEST_FAILED: '请求失败',
  DEPOSIT_CREATE: '登记押金',
  DEPOSIT_REVERSE: '押金冲正',
  DISCHARGE_SETTLEMENT: '出院结算',
  ALERT_RESOLVE: '处理欠费提醒',
  ORDER_CREATE: '开立医嘱',
  ORDER_SUBMIT: '提交医嘱',
  ORDER_STOP: '停止医嘱',
  ORDER_CANCEL: '撤销医嘱',
  ORDER_COMPLETE: '完成医嘱',
  ORDER_EXECUTE: '执行医嘱',
  EXECUTION_REVERSE: '执行冲正',
  ORDER_DISPENSE: '药房发药',
  ORDER_ADMINISTER: '护士给药',
  ORDER_PERFORM: '非药品执行',
  ORDER_EXECUTION_REVERSE: '分环节执行冲正',
  NURSING_OBSERVATION_CREATE: '记录护理观察',
  INPATIENT_RESULT_CREATE: '记录住院检查检验结果',
  INPATIENT_RESULT_AMEND: '更正住院检查检验结果',
  INPATIENT_DIAGNOSIS_CREATE: '记录住院诊断',
  INPATIENT_DIAGNOSIS_RESOLVE: '更新住院诊断状态',
  INPATIENT_NOTE_CREATE: '签署住院临床记录',
  INPATIENT_NOTE_AMEND: '更正住院临床记录',
  ORDER_CATALOG_SAVE: '医嘱项目资料维护',
  ORDER_TEMPLATE_CREATE: '创建项目模板',
  ORDER_TEMPLATE_DISABLE: '停用项目模板',
}

export const auditObjectLabels: LabelMap = {
  request: '系统请求',
  bed: '床位',
  user_account: '系统用户',
  patient: '患者',
  admission: '住院记录',
  deposit_transaction: '押金流水',
  settlement: '结算单',
  account_alert: '欠费提醒',
  medical_order: '医嘱',
  execution_record: '执行记录',
  medical_item: '医疗项目',
  order_template: '医嘱项目模板',
  nursing_observation: '护理观察记录',
  inpatient_result: '住院检查检验结果',
  inpatient_diagnosis: '住院诊断',
  inpatient_clinical_note: '住院临床记录',
}

export const consciousnessLabels: LabelMap = {
  CLEAR: '清醒',
  CONFUSED: '意识模糊',
  DROWSY: '嗜睡',
  UNRESPONSIVE: '无反应',
}

export const resultAbnormalLabels: LabelMap = {
  NORMAL: '正常',
  LOW: '偏低',
  HIGH: '偏高',
  CRITICAL: '危急',
  UNMARKED: '未标记',
}

export const diagnosisTypeLabels: LabelMap = {
  ADMISSION: '入院诊断',
  PRIMARY: '主要诊断',
  SECONDARY: '次要诊断',
  COMPLICATION: '并发症',
  DISCHARGE: '出院诊断',
}

export const clinicalStatusLabels: LabelMap = {
  ACTIVE: '现症',
  RESOLVED: '已解决',
  RULED_OUT: '已排除',
}

export const clinicalNoteTypeLabels: LabelMap = {
  ADMISSION_ASSESSMENT: '入院评估',
  PROGRESS: '病程记录',
  CRITICAL_EVENT: '病情变化记录',
  DISCHARGE_SUMMARY: '出院小结',
  OTHER: '其他住院记录',
}

// 原始编码仍留在审计记录中；未知编码不作为英文正文展示。
export function auditLabel(labels: LabelMap, value: unknown, fallback: string) {
  if (value === null || value === undefined || value === '') return '—'
  const code = String(value)
  return labels[code] || (/^[\u3400-\u9fff\s]+$/.test(code) ? code : fallback)
}

export function auditObjectLabel(type: unknown, id: unknown) {
  const name = auditLabel(auditObjectLabels, type, '其他对象')
  return id === null || id === undefined || id === '' ? name : `${name}（编号：${id}）`
}

export const auditResultLabels: LabelMap = {
  SUCCESS: '成功',
  FAILURE: '失败',
}

export const bedEndReasonLabels: LabelMap = {
  TRANSFER: '转床',
  DISCHARGE: '出院',
}

export function enumLabel(labels: LabelMap, value: unknown, empty = '—') {
  if (value === null || value === undefined || value === '') return empty
  const code = String(value)
  return labels[code] || code
}

export function enumListLabel(labels: LabelMap, value: unknown) {
  if (Array.isArray(value)) return value.map((item) => enumLabel(labels, item)).join('、')
  if (value === null || value === undefined || value === '') return '—'
  return String(value).split(',').map((item) => enumLabel(labels, item.trim())).join('、')
}
