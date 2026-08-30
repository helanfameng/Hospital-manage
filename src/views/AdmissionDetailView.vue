<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { Row } from '@/types/api'
import { admissionStatusLabels, bedEndReasonLabels, clinicalNoteTypeLabels, clinicalStatusLabels, consciousnessLabels, depositTypeLabels, diagnosisTypeLabels, enumLabel, medicalItemTypeLabels, orderStatusLabels, resultAbnormalLabels } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const auth = useAuthStore()
const id = computed(() => Number(route.params.id))
const busy = ref(false)
const page = ref(1)
const doctors = ref<Row[]>([])
const detail = ref<Row>({})
const orders = ref<any>({ items: [] })
const deposits = ref<Row[]>([])
const charges = ref<Row[]>([])
const observations = ref<Row[]>([])
const executions = ref<Row[]>([])
const results = ref<Row[]>([])
const diagnoses = ref<Row[]>([])
const clinicalNotes = ref<Row[]>([])
const diagnosisCatalog = ref<Row[]>([])
const clinicalDoctors = ref<Row[]>([])
const availableBeds = ref<Row[]>([])
const transferDialog = ref(false)
const observationDialog = ref(false)
const observationBusy = ref(false)
const resultDialog = ref(false)
const resultBusy = ref(false)
const diagnosisDialog = ref(false)
const diagnosisResolveDialog = ref(false)
const diagnosisBusy = ref(false)
const noteDialog = ref(false)
const noteBusy = ref(false)
const diagnosisResolveTarget = ref<Row>({})
const transfer = reactive({ targetBedId: undefined as number | undefined, targetDoctorId: undefined as number | undefined, reason: '' })
const observation = reactive({
  requestNo: '', observedAt: '', temperatureC: undefined as number | undefined,
  pulseRate: undefined as number | undefined, respiratoryRate: undefined as number | undefined,
  systolicPressure: undefined as number | undefined, diastolicPressure: undefined as number | undefined,
  oxygenSaturation: undefined as number | undefined, consciousness: '', note: '',
})
const resultForm = reactive({
  executionId: undefined as number | undefined, resultNo: '', analyteCode: '', analyteName: '',
  resultValue: undefined as number | undefined, resultText: '', resultUnit: '', referenceRange: '',
  abnormalFlag: 'UNMARKED', reportedAt: '', amendsResultId: undefined as number | undefined,
  amendmentReason: '',
})
const diagnosisForm = reactive({
  requestNo: '', diagnosisCatalogId: undefined as number | undefined, diagnosisType: 'PRIMARY',
  presentOnAdmission: false, diagnosedAt: '', diagnosedBy: undefined as number | undefined, note: '',
})
const diagnosisResolveForm = reactive({ status: 'RESOLVED', resolvedAt: '', note: '' })
const noteForm = reactive({
  requestNo: '', noteType: 'PROGRESS', title: '', content: '', authoredBy: undefined as number | undefined,
  authoredAt: '', signedAt: '', amendsNoteId: undefined as number | undefined, amendmentReason: '',
})
const balance = computed(() => Number(detail.value.available_balance || 0))

const targetDepartment = computed(() => availableBeds.value.find(b => b.id === transfer.targetBedId)?.department_id)
const targetDoctors = computed(() => doctors.value.filter(d => d.active && d.department_id === targetDepartment.value))
const crossDepartment = computed(() => targetDepartment.value && targetDepartment.value !== detail.value.department_id)
const resultExecutions = computed(() => executions.value.filter(row => row.status === 'SUCCESS' && !row.is_reversed && row.phase === 'PERFORM' && ['EXAMINATION', 'LAB'].includes(row.item_type)))
const resultDisplay = (row: Row) => row.result_text || (row.result_value === null || row.result_value === undefined ? '—' : row.result_value)
watch(() => transfer.targetBedId, () => { transfer.targetDoctorId = undefined })
async function load() {
  const current = id.value
  const values = await Promise.all([
    getData<Row>(`/admissions/${current}`), getData<any>('/orders', { admissionId: current, page: page.value, size: 20 }),
    getData<Row[]>('/deposits', { admissionId: current }), getData<Row[]>('/charges', { admissionId: current }),
    getData<Row[]>(`/admissions/${current}/observations`),
    getData<Row[]>('/orders/executions/history', { admissionId: current }),
    getData<Row[]>(`/admissions/${current}/results`),
    getData<Row[]>(`/admissions/${current}/diagnoses`),
    getData<Row[]>(`/admissions/${current}/clinical-notes`),
  ])
  if (current !== id.value) return
  ;[detail.value, orders.value, deposits.value, charges.value, observations.value, executions.value, results.value, diagnoses.value, clinicalNotes.value] = values
}
function localDateTimeValue(date = new Date(Date.now() - 1000)) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
function openObservation() {
  Object.assign(observation, {
    requestNo: `OBS-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    observedAt: localDateTimeValue(), temperatureC: undefined, pulseRate: undefined,
    respiratoryRate: undefined, systolicPressure: undefined, diastolicPressure: undefined,
    oxygenSaturation: undefined, consciousness: '', note: '',
  })
  observationDialog.value = true
}
async function submitObservation() {
  if (observationBusy.value) return
  if (!observation.observedAt) {
    ElMessage.warning('请选择观察时间')
    return
  }
  const measurements = [observation.temperatureC, observation.pulseRate, observation.respiratoryRate,
    observation.systolicPressure, observation.diastolicPressure, observation.oxygenSaturation, observation.consciousness]
  if (measurements.every(value => value === undefined || value === null || value === '')) {
    ElMessage.warning('至少填写一项生命体征或意识状态')
    return
  }
  if ((observation.systolicPressure === undefined) !== (observation.diastolicPressure === undefined)) {
    ElMessage.warning('收缩压和舒张压必须同时填写')
    return
  }
  if (observation.systolicPressure !== undefined && observation.diastolicPressure !== undefined
      && observation.systolicPressure <= observation.diastolicPressure) {
    ElMessage.warning('收缩压必须高于舒张压')
    return
  }
  observationBusy.value = true
  try {
    await postData(`/admissions/${id.value}/observations`, {
      ...observation,
      observedAt: new Date(observation.observedAt).toISOString(),
      consciousness: observation.consciousness || null,
      note: observation.note.trim() || null,
    })
    ElMessage.success('护理观察记录成功')
    observationDialog.value = false
    await load()
  } catch (error) { console.error(error) }
  finally { observationBusy.value = false }
}
function openResult(source?: Row) {
  Object.assign(resultForm, {
    executionId: source?.execution_id === undefined ? undefined : Number(source.execution_id),
    resultNo: `RES-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    analyteCode: source?.analyte_code || '', analyteName: source?.analyte_name || '',
    resultValue: source?.result_value === null || source?.result_value === undefined ? undefined : Number(source.result_value),
    resultText: source?.result_text || '', resultUnit: source?.result_unit || '',
    referenceRange: source?.reference_range || '', abnormalFlag: source?.abnormal_flag || 'UNMARKED',
    reportedAt: localDateTimeValue(), amendsResultId: source?.id === undefined ? undefined : Number(source.id),
    amendmentReason: '',
  })
  resultDialog.value = true
}
async function submitResult() {
  if (resultBusy.value) return
  if (!resultForm.executionId || !resultForm.analyteCode.trim() || !resultForm.analyteName.trim()) {
    ElMessage.warning('请填写来源执行、检验项目编码和名称')
    return
  }
  if (resultForm.resultValue == null && !resultForm.resultText.trim()) {
    ElMessage.warning('结果值和结果文本至少填写一项')
    return
  }
  if (!resultForm.reportedAt) {
    ElMessage.warning('请选择报告时间')
    return
  }
  if (resultForm.amendsResultId && !resultForm.amendmentReason.trim()) {
    ElMessage.warning('更正结果必须填写更正原因')
    return
  }
  resultBusy.value = true
  try {
    await postData(`/admissions/${id.value}/results`, {
      requestNo: resultForm.resultNo,
      executionId: resultForm.executionId,
      analyteCode: resultForm.analyteCode,
      analyteName: resultForm.analyteName,
      resultValue: resultForm.resultValue,
      abnormalFlag: resultForm.abnormalFlag,
      reportedAt: new Date(resultForm.reportedAt).toISOString(),
      resultText: resultForm.resultText.trim() || null,
      resultUnit: resultForm.resultUnit.trim() || null,
      referenceRange: resultForm.referenceRange.trim() || null,
      amendsResultId: resultForm.amendsResultId || null,
      amendmentReason: resultForm.amendmentReason.trim() || null,
    })
    ElMessage.success(resultForm.amendsResultId ? '结果更正成功' : '住院检查检验结果记录成功')
    resultDialog.value = false
    await load()
  } catch (error) { console.error(error) }
  finally { resultBusy.value = false }
}
async function loadClinicalOptions() {
  const values = await Promise.all([
    diagnosisCatalog.value.length ? Promise.resolve(diagnosisCatalog.value) : getData<Row[]>('/diagnosis-catalog'),
    getData<Row[]>('/admin/staff', { staffType: 'DOCTOR', departmentId: detail.value.department_id }),
  ])
  diagnosisCatalog.value = values[0]
  clinicalDoctors.value = values[1].filter(row => row.active)
}
async function openDiagnosis() {
  await loadClinicalOptions()
  Object.assign(diagnosisForm, {
    requestNo: `DX-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    diagnosisCatalogId: undefined, diagnosisType: 'PRIMARY', presentOnAdmission: false,
    diagnosedAt: localDateTimeValue(), diagnosedBy: detail.value.attending_doctor_id || clinicalDoctors.value[0]?.id, note: '',
  })
  diagnosisDialog.value = true
}
async function submitDiagnosis() {
  if (diagnosisBusy.value) return
  if (!diagnosisForm.diagnosisCatalogId || !diagnosisForm.diagnosedBy || !diagnosisForm.diagnosedAt) {
    ElMessage.warning('请填写诊断目录、诊断医生和诊断时间')
    return
  }
  diagnosisBusy.value = true
  try {
    await postData(`/admissions/${id.value}/diagnoses`, {
      ...diagnosisForm,
      diagnosedAt: new Date(diagnosisForm.diagnosedAt).toISOString(),
      note: diagnosisForm.note.trim() || null,
    })
    ElMessage.success('住院诊断记录成功')
    diagnosisDialog.value = false
    await load()
  } catch (error) { console.error(error) }
  finally { diagnosisBusy.value = false }
}
function openDiagnosisResolve(row: Row) {
  diagnosisResolveTarget.value = row
  Object.assign(diagnosisResolveForm, { status: 'RESOLVED', resolvedAt: localDateTimeValue(), note: '' })
  diagnosisResolveDialog.value = true
}
async function resolveDiagnosis() {
  if (diagnosisBusy.value || !diagnosisResolveTarget.value.id) return
  if (!diagnosisResolveForm.resolvedAt) {
    ElMessage.warning('请选择解决时间')
    return
  }
  diagnosisBusy.value = true
  try {
    await postData(`/admissions/${id.value}/diagnoses/${diagnosisResolveTarget.value.id}/resolve`, {
      ...diagnosisResolveForm,
      resolvedAt: new Date(diagnosisResolveForm.resolvedAt).toISOString(),
      note: diagnosisResolveForm.note.trim() || null,
    })
    ElMessage.success('住院诊断状态已更新')
    diagnosisResolveDialog.value = false
    await load()
  } catch (error) { console.error(error) }
  finally { diagnosisBusy.value = false }
}
async function openClinicalNote(source?: Row) {
  await loadClinicalOptions()
  Object.assign(noteForm, {
    requestNo: `NOTE-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    noteType: source?.note_type || 'PROGRESS', title: source?.title || '', content: source?.content || '',
    authoredBy: source?.authored_by === undefined ? (detail.value.attending_doctor_id || clinicalDoctors.value[0]?.id) : Number(source.authored_by),
    authoredAt: localDateTimeValue(), signedAt: localDateTimeValue(),
    amendsNoteId: source?.id === undefined ? undefined : Number(source.id), amendmentReason: '',
  })
  noteDialog.value = true
}
async function submitClinicalNote() {
  if (noteBusy.value) return
  if (!noteForm.authoredBy || !noteForm.title.trim() || !noteForm.content.trim() || !noteForm.authoredAt || !noteForm.signedAt) {
    ElMessage.warning('请填写作者、标题、内容和签署时间')
    return
  }
  if (noteForm.amendsNoteId && !noteForm.amendmentReason.trim()) {
    ElMessage.warning('更正临床记录必须填写更正原因')
    return
  }
  noteBusy.value = true
  try {
    await postData(`/admissions/${id.value}/clinical-notes`, {
      ...noteForm,
      authoredAt: new Date(noteForm.authoredAt).toISOString(),
      signedAt: new Date(noteForm.signedAt).toISOString(),
      title: noteForm.title.trim(), content: noteForm.content.trim(),
      amendmentReason: noteForm.amendmentReason.trim() || null,
      amendsNoteId: noteForm.amendsNoteId || null,
    })
    ElMessage.success(noteForm.amendsNoteId ? '临床记录更正成功' : '住院临床记录签署成功')
    noteDialog.value = false
    await load()
  } catch (error) { console.error(error) }
  finally { noteBusy.value = false }
}
async function openTransfer() {
  Object.assign(transfer, { targetBedId: undefined, targetDoctorId: undefined, reason: '' })
  const values = await Promise.all([getData<Row[]>('/beds', { status: 'AVAILABLE' }), getData<Row[]>('/admin/staff', { staffType: 'DOCTOR' })])
  availableBeds.value = values[0].filter(b => b.active)
  doctors.value = values[1]
  transferDialog.value = true
}
async function submitTransfer() {
  if (busy.value) return
  busy.value = true
  const current = id.value
  try {
    await ElMessageBox.confirm('确认转床？跨科转床将同时变更科室与主管医生。', '确认转床', { type: 'warning' })
    await postData(`/admissions/${current}/transfer`, transfer)
    ElMessage.success('转床成功')
    transferDialog.value = false
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') console.error(error) }
  finally { busy.value = false }
}
async function discharge(withdraw = false) {
  if (busy.value) return
  busy.value = true
  const current = id.value
  try {
    await ElMessageBox.confirm(withdraw ? '撤回后可继续诊疗，确认？' : '申请后暂停诊疗操作，须完成结算才会释放床位。确认？', '出院状态确认')
    await postData(`/admissions/${current}/${withdraw ? 'withdraw-discharge' : 'request-discharge'}`)
    ElMessage.success(withdraw ? '已撤回出院申请' : '已申请出院，请到出院结算办理结账')
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') console.error(error) }
  finally { busy.value = false }
}
watch(id, () => { detail.value = {}; orders.value = { items: [] }; deposits.value = []; charges.value = []; observations.value = []; executions.value = []; results.value = []; diagnoses.value = []; clinicalNotes.value = []; page.value = 1; void load() }, { immediate: true })
</script>

<template>
  <div>
    <div class="page-header">
      <div><h2>{{ detail.patient_name }} <el-tag>{{ detail.admission_no }}</el-tag></h2><p>{{ detail.department_name }} · {{ detail.attending_doctor_name }} · {{ detail.ward_name }} {{ detail.room_no }}-{{ detail.bed_no }}</p></div>
      <div v-if="auth.can('ADMISSION_MANAGE')"><template v-if="detail.status === 'IN_HOSPITAL'"><el-button :disabled="busy" @click="openTransfer">转床/转科</el-button><el-button :disabled="busy" type="primary" @click="discharge()">申请出院</el-button></template><el-button v-if="detail.status === 'PENDING_DISCHARGE'" :disabled="busy" @click="discharge(true)">撤回出院申请</el-button></div>
    </div>
    <div class="metric-grid">
      <div class="metric-card"><span class="label">押金余额</span><div class="value">¥{{ detail.deposit_balance || 0 }}</div></div>
      <div class="metric-card"><span class="label">累计费用</span><div class="value">¥{{ detail.total_charge || 0 }}</div></div>
      <div class="metric-card"><span class="label">可用余额</span><div class="value" :style="{ color: balance < 0 ? '#dc2626' : '' }">¥{{ detail.available_balance || 0 }}</div></div>
      <div class="metric-card"><span class="label">住院状态</span><div class="value" style="font-size:22px">{{ enumLabel(admissionStatusLabels, detail.status) }}</div></div>
    </div>
    <div class="panel">
      <el-tabs>
        <el-tab-pane label="床位历史"><el-table :data="detail.bedHistory"><el-table-column prop="ward_name" label="病区" /><el-table-column label="床位"><template #default="{ row }">{{ row.room_no }}-{{ row.bed_no }}</template></el-table-column><el-table-column label="开始时间"><template #default="{ row }">{{ formatDateTime(row.start_time) }}</template></el-table-column><el-table-column label="结束时间"><template #default="{ row }">{{ formatDateTime(row.end_time) }}</template></el-table-column><el-table-column label="结束原因"><template #default="{ row }">{{ enumLabel(bedEndReasonLabels, row.end_reason) }}</template></el-table-column></el-table></el-tab-pane>
        <el-tab-pane label="护理观察"><div class="tab-toolbar"><span class="muted">按观察时间倒序，原始记录只读保留</span><el-button v-if="auth.can('NURSING_RECORD') && ['IN_HOSPITAL', 'PENDING_DISCHARGE'].includes(detail.status)" type="primary" @click="openObservation">记录生命体征</el-button></div><el-table :data="observations"><el-table-column label="观察时间" min-width="160"><template #default="{ row }">{{ formatDateTime(row.observed_at) }}</template></el-table-column><el-table-column prop="temperature_c" label="体温℃" width="90" /><el-table-column prop="pulse_rate" label="脉搏/分" width="95" /><el-table-column prop="respiratory_rate" label="呼吸/分" width="95" /><el-table-column label="血压mmHg" width="110"><template #default="{ row }">{{ row.systolic_pressure || row.diastolic_pressure ? `${row.systolic_pressure || '—'}/${row.diastolic_pressure || '—'}` : '—' }}</template></el-table-column><el-table-column prop="oxygen_saturation" label="血氧%" width="85" /><el-table-column label="意识" width="100"><template #default="{ row }">{{ enumLabel(consciousnessLabels, row.consciousness) }}</template></el-table-column><el-table-column prop="recorded_by_name" label="记录人" width="110" /><el-table-column prop="note" label="备注" min-width="180" /></el-table></el-tab-pane>
        <el-tab-pane label="检查检验结果"><div class="tab-toolbar"><span class="muted">结果绑定住院检查/检验执行，原始结果只读保留</span><el-button v-if="auth.can('RESULT_RECORD') && resultExecutions.length" type="primary" @click="openResult()">登记结果</el-button></div><el-table :data="results"><el-table-column prop="order_no" label="医嘱号" min-width="170" show-overflow-tooltip /><el-table-column label="项目" min-width="150"><template #default="{ row }">{{ row.item_name }}<div class="muted">{{ enumLabel(medicalItemTypeLabels, row.item_type) }}</div></template></el-table-column><el-table-column label="检验项目" min-width="150"><template #default="{ row }">{{ row.analyte_name }}<div class="muted">{{ row.analyte_code }}</div></template></el-table-column><el-table-column label="结果" min-width="160"><template #default="{ row }">{{ resultDisplay(row) }}<span v-if="row.result_unit"> {{ row.result_unit }}</span><div class="muted">参考：{{ row.reference_range || '—' }}</div></template></el-table-column><el-table-column label="异常" width="85"><template #default="{ row }">{{ enumLabel(resultAbnormalLabels, row.abnormal_flag) }}</template></el-table-column><el-table-column label="状态" width="85"><template #default="{ row }">{{ row.result_status === 'AMENDED' ? '已更正' : '原始' }}</template></el-table-column><el-table-column label="报告时间" width="160"><template #default="{ row }">{{ formatDateTime(row.reported_at) }}</template></el-table-column><el-table-column prop="verified_by_name" label="审核人" width="100" /><el-table-column label="操作" width="85" fixed="right"><template #default="{ row }"><el-button v-if="auth.can('RESULT_RECORD') && !row.is_superseded" link type="primary" @click="openResult(row)">更正</el-button></template></el-table-column></el-table></el-tab-pane>
        <el-tab-pane label="医嘱"><el-table :data="orders.items"><el-table-column prop="order_no" label="医嘱号" /><el-table-column label="状态"><template #default="{ row }">{{ enumLabel(orderStatusLabels, row.status) }}</template></el-table-column><el-table-column prop="prescriber_name" label="开立医生" /><el-table-column prop="item_count" label="项目数" /></el-table><el-pagination v-model:current-page="page" :page-size="20" :total="orders.total || 0" layout="prev, pager, next, total" @current-change="load" /></el-tab-pane>
        <el-tab-pane label="费用"><el-table :data="charges"><el-table-column label="时间"><template #default="{ row }">{{ formatDateTime(row.charged_at) }}</template></el-table-column><el-table-column prop="item_name" label="项目" /><el-table-column prop="quantity" label="数量" /><el-table-column prop="amount" label="金额" /></el-table></el-tab-pane>
        <el-tab-pane label="押金"><el-table :data="deposits"><el-table-column label="时间"><template #default="{ row }">{{ formatDateTime(row.transaction_time) }}</template></el-table-column><el-table-column label="类型"><template #default="{ row }">{{ enumLabel(depositTypeLabels, row.transaction_type) }}</template></el-table-column><el-table-column prop="amount" label="金额" /><el-table-column prop="operator_name" label="操作人" /></el-table></el-tab-pane>
        <el-tab-pane label="诊疗记录">
          <div class="tab-toolbar"><span class="muted">诊断保留状态变化，临床记录签署后只读；更正以新记录追加</span><div><el-button v-if="auth.can('DIAGNOSIS_RECORD') && !['PENDING', 'CANCELLED'].includes(detail.status)" type="primary" @click="openDiagnosis">登记诊断</el-button><el-button v-if="auth.can('CLINICAL_RECORD') && !['PENDING', 'CANCELLED'].includes(detail.status)" type="primary" @click="openClinicalNote()">新增临床记录</el-button></div></div>
          <el-table :data="diagnoses" style="margin-bottom: 20px"><el-table-column label="诊断" min-width="210"><template #default="{ row }">{{ row.diagnosis_name_snapshot }}<div class="muted">{{ row.coding_system_snapshot }} · {{ row.diagnosis_code_snapshot }}</div></template></el-table-column><el-table-column label="类型" width="105"><template #default="{ row }">{{ enumLabel(diagnosisTypeLabels, row.diagnosis_type) }}</template></el-table-column><el-table-column label="状态" width="95"><template #default="{ row }">{{ enumLabel(clinicalStatusLabels, row.clinical_status) }}</template></el-table-column><el-table-column label="入院时已存在" width="110"><template #default="{ row }">{{ row.present_on_admission ? '是' : '否' }}</template></el-table-column><el-table-column prop="diagnosed_by_name" label="诊断医生" width="105" /><el-table-column label="诊断时间" width="160"><template #default="{ row }">{{ formatDateTime(row.diagnosed_at) }}</template></el-table-column><el-table-column prop="diagnosis_note" label="备注" min-width="160" show-overflow-tooltip /><el-table-column label="操作" width="95" fixed="right"><template #default="{ row }"><el-button v-if="auth.can('DIAGNOSIS_RECORD') && row.clinical_status === 'ACTIVE'" link type="primary" @click="openDiagnosisResolve(row)">结束诊断</el-button></template></el-table-column></el-table>
          <el-table :data="clinicalNotes"><el-table-column label="记录类型" width="125"><template #default="{ row }">{{ enumLabel(clinicalNoteTypeLabels, row.note_type) }}<div v-if="row.note_status === 'AMENDED'" class="muted">已更正</div></template></el-table-column><el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip /><el-table-column prop="content" label="记录内容" min-width="300" show-overflow-tooltip /><el-table-column prop="authored_by_name" label="作者" width="100" /><el-table-column label="签署时间" width="160"><template #default="{ row }">{{ formatDateTime(row.signed_at) }}</template></el-table-column><el-table-column prop="signed_by_name" label="签署人" width="100" /><el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button v-if="auth.can('CLINICAL_RECORD') && !row.is_superseded" link type="primary" @click="openClinicalNote(row)">更正</el-button></template></el-table-column></el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
    <el-dialog v-model="diagnosisDialog" title="登记住院诊断" width="620px">
      <el-form label-position="top"><div class="clinical-grid"><el-form-item label="诊断目录" required><el-select v-model="diagnosisForm.diagnosisCatalogId" filterable class="full-width"><el-option v-for="row in diagnosisCatalog" :key="row.id" :value="Number(row.id)" :label="`${row.code} · ${row.name}`" /></el-select></el-form-item><el-form-item label="诊断类型" required><el-select v-model="diagnosisForm.diagnosisType" class="full-width"><el-option v-for="(label, value) in diagnosisTypeLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item><el-form-item label="诊断医生" required><el-select v-model="diagnosisForm.diagnosedBy" class="full-width"><el-option v-for="row in clinicalDoctors" :key="row.id" :value="Number(row.id)" :label="`${row.name}（${row.staff_no}）`" /></el-select></el-form-item><el-form-item label="诊断时间" required><el-date-picker v-model="diagnosisForm.diagnosedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item></div><el-form-item><el-checkbox v-model="diagnosisForm.presentOnAdmission">入院时已存在</el-checkbox></el-form-item><el-form-item label="诊断备注"><el-input v-model="diagnosisForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="diagnosisDialog=false">取消</el-button><el-button type="primary" :loading="diagnosisBusy" @click="submitDiagnosis">保存诊断</el-button></template>
    </el-dialog>
    <el-dialog v-model="diagnosisResolveDialog" title="结束住院诊断" width="520px">
      <p class="muted">{{ diagnosisResolveTarget.diagnosis_name_snapshot }}（{{ diagnosisResolveTarget.diagnosis_code_snapshot }}）</p><el-form label-position="top"><el-form-item label="状态" required><el-select v-model="diagnosisResolveForm.status" class="full-width"><el-option label="已解决" value="RESOLVED" /><el-option label="已排除" value="RULED_OUT" /></el-select></el-form-item><el-form-item label="解决时间" required><el-date-picker v-model="diagnosisResolveForm.resolvedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item><el-form-item label="解决说明"><el-input v-model="diagnosisResolveForm.note" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="diagnosisResolveDialog=false">取消</el-button><el-button type="primary" :loading="diagnosisBusy" @click="resolveDiagnosis">保存状态</el-button></template>
    </el-dialog>
    <el-dialog v-model="noteDialog" :title="noteForm.amendsNoteId ? '更正住院临床记录' : '签署住院临床记录'" width="760px">
      <el-form label-position="top"><div class="clinical-grid"><el-form-item label="记录类型" required><el-select v-model="noteForm.noteType" class="full-width" :disabled="Boolean(noteForm.amendsNoteId)"><el-option v-for="(label, value) in clinicalNoteTypeLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item><el-form-item label="记录作者" required><el-select v-model="noteForm.authoredBy" class="full-width"><el-option v-for="row in clinicalDoctors" :key="row.id" :value="Number(row.id)" :label="`${row.name}（${row.staff_no}）`" /></el-select></el-form-item><el-form-item label="起草时间" required><el-date-picker v-model="noteForm.authoredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item><el-form-item label="签署时间" required><el-date-picker v-model="noteForm.signedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item></div><el-form-item label="标题" required><el-input v-model="noteForm.title" maxlength="200" /></el-form-item><el-form-item label="记录内容" required><el-input v-model="noteForm.content" type="textarea" :rows="9" maxlength="10000" show-word-limit /></el-form-item><el-form-item v-if="noteForm.amendsNoteId" label="更正原因" required><el-input v-model="noteForm.amendmentReason" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="noteDialog=false">取消</el-button><el-button type="primary" :loading="noteBusy" @click="submitClinicalNote">签署保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="transferDialog" title="办理转床" width="520px">
      <el-form label-position="top"><el-form-item label="目标床位"><el-select v-model="transfer.targetBedId" filterable class="full-width"><el-option v-for="b in availableBeds" :key="b.id" :value="b.id" :label="`${b.department_name} / ${b.ward_name} / ${b.room_no}-${b.bed_no}`" /></el-select></el-form-item><el-form-item :label="crossDepartment ? '目标科室主管医生（必选）' : '主管医生（不选则保持原医生）'"><el-select v-model="transfer.targetDoctorId" clearable class="full-width"><el-option v-for="d in targetDoctors" :key="d.id" :value="d.id" :label="d.name" /></el-select></el-form-item><el-form-item label="转床原因"><el-input v-model="transfer.reason" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="transferDialog=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!transfer.targetBedId || !transfer.reason.trim() || Boolean(crossDepartment && !transfer.targetDoctorId)" @click="submitTransfer">确认转床</el-button></template>
    </el-dialog>
    <el-dialog v-model="observationDialog" title="记录护理观察" width="640px">
      <el-form label-position="top"><el-form-item label="观察时间" required><el-date-picker v-model="observation.observedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item><div class="observation-grid"><el-form-item label="体温（℃）"><el-input-number v-model="observation.temperatureC" :min="30" :max="45" :precision="1" :step="0.1" controls-position="right" /></el-form-item><el-form-item label="脉搏（次/分）"><el-input-number v-model="observation.pulseRate" :min="20" :max="250" controls-position="right" /></el-form-item><el-form-item label="呼吸（次/分）"><el-input-number v-model="observation.respiratoryRate" :min="5" :max="80" controls-position="right" /></el-form-item><el-form-item label="收缩压（mmHg）"><el-input-number v-model="observation.systolicPressure" :min="50" :max="300" controls-position="right" /></el-form-item><el-form-item label="舒张压（mmHg）"><el-input-number v-model="observation.diastolicPressure" :min="20" :max="200" controls-position="right" /></el-form-item><el-form-item label="血氧饱和度（%）"><el-input-number v-model="observation.oxygenSaturation" :min="0" :max="100" :precision="2" :step="0.01" controls-position="right" /></el-form-item></div><el-form-item label="意识状态"><el-select v-model="observation.consciousness" clearable class="full-width"><el-option v-for="(label, value) in consciousnessLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item label="护理备注"><el-input v-model="observation.note" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="observationDialog=false">取消</el-button><el-button type="primary" :loading="observationBusy" @click="submitObservation">保存记录</el-button></template>
    </el-dialog>
    <el-dialog v-model="resultDialog" :title="resultForm.amendsResultId ? '更正检查检验结果' : '登记检查检验结果'" width="680px">
      <el-form label-position="top"><el-form-item label="来源检查/检验执行" required><el-select v-model="resultForm.executionId" filterable class="full-width" :disabled="Boolean(resultForm.amendsResultId)"><el-option v-for="row in resultExecutions" :key="row.id" :value="Number(row.id)" :label="`${row.order_no} · ${row.item_name} · ${formatDateTime(row.execution_time)}`" /></el-select></el-form-item><div class="result-grid"><el-form-item label="检验项目编码" required><el-input v-model="resultForm.analyteCode" maxlength="32" :disabled="Boolean(resultForm.amendsResultId)" /></el-form-item><el-form-item label="检验项目名称" required><el-input v-model="resultForm.analyteName" maxlength="100" :disabled="Boolean(resultForm.amendsResultId)" /></el-form-item><el-form-item label="数值结果"><el-input-number v-model="resultForm.resultValue" :precision="6" controls-position="right" /></el-form-item><el-form-item label="结果单位"><el-input v-model="resultForm.resultUnit" maxlength="32" /></el-form-item><el-form-item label="参考范围"><el-input v-model="resultForm.referenceRange" maxlength="100" /></el-form-item><el-form-item label="异常标记"><el-select v-model="resultForm.abnormalFlag" class="full-width"><el-option v-for="(label, value) in resultAbnormalLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item></div><el-form-item label="文本结果"><el-input v-model="resultForm.resultText" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item><el-form-item label="报告时间" required><el-date-picker v-model="resultForm.reportedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" /></el-form-item><el-form-item v-if="resultForm.amendsResultId" label="更正原因" required><el-input v-model="resultForm.amendmentReason" type="textarea" :rows="2" maxlength="255" /></el-form-item></el-form>
      <template #footer><el-button @click="resultDialog=false">取消</el-button><el-button type="primary" :loading="resultBusy" @click="submitResult">保存结果</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tab-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.observation-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.observation-grid :deep(.el-input-number) { width: 100%; }
.result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.result-grid :deep(.el-input-number) { width: 100%; }
.clinical-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
@media (max-width: 720px) { .observation-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .result-grid, .clinical-grid { grid-template-columns: 1fr; } }
</style>
