<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from '@/components/AdmissionPicker.vue'
import MedicationProgress from '@/components/MedicationProgress.vue'
import { useAuthStore } from '@/stores/auth'
import type { PageResult, Row } from '@/types/api'
import { enumLabel, medicalItemTypeLabels, orderTypeLabels } from '@/utils/labels'
import { executionQuantity, nonDrugOrderCategories } from '@/utils/orders'
import { formatDateTime } from '@/utils/datetime'
const auth = useAuthStore()
const tasks = ref<Row[]>([]), history = ref<Row[]>([]), departments = ref<Row[]>([]), admissionId = ref<number>()
const phase = ref(auth.can('PHARMACY_EXECUTE') ? 'DISPENSE' : auth.can('MEDICATION_ADMINISTER') ? 'ADMINISTER' : 'PERFORM')
const itemType = ref(''), keyword = ref(''), administrationMode = ref(''), timeliness = ref(''), dispenseStatus = ref(''), departmentId = ref<number>(), sort = ref('PLANNED_AT_ASC'), scheduledRange = ref<string[]>([])
const futureHours = ref(2), page = ref(1), total = ref(0), loading = ref(false), busy = ref(false), tab = ref('pending')
const phases: Record<string,string> = { DISPENSE: '药房发药', ADMINISTER: '护士给药', PERFORM: '非药品项目执行' }
const requestKeys = new Map<string,string>()
const preview = computed(() => tab.value === 'future')
const canFilterDepartments = computed(() => Boolean(auth.user?.roles?.some((role: string) => ['ADMIN','PHARMACY','ADMISSION','CASHIER'].includes(role))))
function members(row: Row) { return [row] }
function resetPage() { page.value = 1; void load() }
async function load() {
  loading.value = true
  try {
    const result = await getData<PageResult<Row> | Row[]>('/orders/tasks', { phase: phase.value, itemType: itemType.value || undefined,
      view: preview.value ? 'FUTURE' : 'EXECUTABLE', windowHours: preview.value ? futureHours.value : undefined,
      keyword: keyword.value || undefined, administrationMode: administrationMode.value || undefined, timeliness: timeliness.value || undefined,
      dispenseStatus: dispenseStatus.value || undefined, departmentId: departmentId.value, sort: sort.value,
      scheduledFrom: scheduledRange.value?.[0] ? new Date(scheduledRange.value[0]).toISOString() : undefined,
      scheduledTo: scheduledRange.value?.[1] ? new Date(scheduledRange.value[1]).toISOString() : undefined,
      page: page.value, size: 20 })
    if (Array.isArray(result)) { tasks.value = result; total.value = result.length }
    else { tasks.value = result.items; total.value = result.total }
  }
  finally { loading.value = false }
}
async function loadDepartments() {
  if (canFilterDepartments.value) departments.value = await getData<Row[]>('/admin/departments')
}
async function loadHistory() {
  const id = admissionId.value; history.value = []
  if (!id) return
  const result = await getData<Row[]>('/orders/executions/history', { admissionId: id })
  if (id === admissionId.value) history.value = result
}
watch(admissionId, loadHistory)
async function execute(row: Row, notDone=false) {
  if (busy.value) return
  busy.value = true
  const key = phase.value+':'+row.order_item_id+':'+notDone
  try {
    const description = members(row).map(r => r.item_name+' '+r.task_quantity+' '+(r.task_unit ?? r.unit)+(r.scheduled_at ? `（计划时间 ${formatDateTime(r.scheduled_at)}）` : '')).join('；')
    let note = '', actualQuantity: number | undefined, quantityVarianceReason = ''
    if (notDone) {
      const result = await ElMessageBox.prompt('记录本次未执行原因（拒绝、暂停、待核对等）；不计费、不扣库存，也不完成医嘱。', '登记未执行', { inputPattern: /\S/, inputErrorMessage: '请填写原因' })
      note = result.value.trim()
    } else {
      if (phase.value==='ADMINISTER' && row.timeliness==='OVERDUE') {
        const result = await ElMessageBox.prompt('该定时给药已逾期 '+row.overdue_minutes+' 分钟；请填写迟发原因。', '迟发给药', { inputPattern: /\S/, inputErrorMessage: '请填写原因', type: 'warning' })
        note = result.value.trim()
      }
      if (['DISPENSE','ADMINISTER'].includes(phase.value)) {
        const result = await ElMessageBox.prompt('患者 '+row.patient_name+'：'+description+'。请输入本次实际'+(phase.value==='DISPENSE'?'发药':'给药')+'数量。', phases[phase.value], {
          inputValue: String(row.task_quantity), inputPattern: /^\d+(\.\d{1,3})?$/, inputErrorMessage: '请输入大于零且最多三位小数的数量', type: 'warning' })
        actualQuantity = Number(result.value)
        if (!(actualQuantity > 0)) throw new Error('实际数量必须大于零')
        if (actualQuantity !== Number(row.task_quantity)) {
          const variance = await ElMessageBox.prompt('实际数量与医嘱/建议数量不同，请填写偏差原因。', '数量偏差说明', { inputPattern: /\S/, inputErrorMessage: '请填写原因', type: 'warning' })
          quantityVarianceReason = variance.value.trim()
        }
      } else await ElMessageBox.confirm('患者 '+row.patient_name+'：'+description+'。记录项目执行；收费项目将产生费用。', phases[phase.value], { type: 'warning' })
    }
    if (!requestKeys.has(key)) requestKeys.set(key, crypto.randomUUID())
    await postData('/orders/executions', { requestNo: requestKeys.get(key), orderItemId: row.order_item_id, scheduleId: row.schedule_id || null,
      phase: phase.value, actualQuantity, quantityVarianceReason: quantityVarianceReason || null, notDone, note })
    requestKeys.delete(key); ElMessage.success(notDone ? '未执行原因已记录' : '本次操作已记录'); await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') await load() }
  finally { busy.value = false }
}
function changePhase() {
  page.value = 1; itemType.value = ''; administrationMode.value = ''; timeliness.value = ''; dispenseStatus.value = ''; scheduledRange.value = []; void load()
}
function changeTab(name: string) {
  if (name === 'future') phase.value = 'ADMINISTER'
  page.value = 1; void load()
}
function canReverse(row: Row) {
  if (row.status!=='SUCCESS' || row.is_reversed) return false
  if (row.phase==='DISPENSE') return auth.can('PHARMACY_EXECUTE')
  if (row.phase==='ADMINISTER') return auth.can('MEDICATION_ADMINISTER')
  return auth.canAny('ORDER_EXECUTE','ORDER_WRITE')
}
async function reverse(row: Row) {
  if (busy.value) return
  busy.value = true
  try {
    const { value } = await ElMessageBox.prompt('冲正 '+row.item_name+' 本次记录（'+executionQuantity(row)+'）。组内记录将整组冲正；实际用药不能通过冲正撤销，请仅用于事实纠错并说明原因。发药整笔退回仅限未给药项目。', '执行纠错 / 退药', { inputPattern: /\S/, inputErrorMessage: '请填写原因', type: 'warning' })
    await postData('/orders/executions/'+row.id+'/reverse', { reason: value.trim() })
    ElMessage.success('冲正已登记，相关账目已同步'); await Promise.all([loadHistory(),load()])
  } catch (e) { if (e !== 'cancel' && e !== 'close') console.error(e) }
  finally { busy.value = false }
}
onMounted(async () => { await loadDepartments(); await load() })
</script>
<template>
  <div><div class="page-header"><div><h2>医嘱执行</h2><p>临时药品一次给药后结束；长期药品按计划时间滚动生成任务。发药和给药均记录实际数量。</p></div></div>
    <div class="panel">
      <el-tabs v-model="tab" @tab-change="changeTab">
        <el-tab-pane name="pending" label="待执行任务">
          <div class="toolbar phase-toolbar">
            <el-radio-group v-model="phase" class="phase-switch" :disabled="busy" @change="page=1;itemType='';load()">
              <el-radio-button v-if="auth.can('PHARMACY_EXECUTE')" value="DISPENSE">药房发药</el-radio-button><el-radio-button v-if="auth.can('MEDICATION_ADMINISTER')" value="ADMINISTER">护士给药</el-radio-button><el-radio-button v-if="auth.canAny('MEDICATION_ADMINISTER','ORDER_WRITE')" value="PERFORM">非药品执行</el-radio-button>
            </el-radio-group>
            <el-select v-if="phase==='PERFORM'" v-model="itemType" clearable placeholder="全部非药品类别" style="width:170px" @change="resetPage"><el-option v-for="(label,key) in nonDrugOrderCategories" :key="key" :label="label" :value="key"/></el-select>
          </div>
          <div class="toolbar task-filters"><el-input v-model="keyword" clearable placeholder="患者 / 医嘱 / 床位" style="width:230px" @change="resetPage"/><el-select v-if="phase==='ADMINISTER'" v-model="administrationMode" clearable placeholder="全部给药方式" style="width:150px" @change="resetPage"><el-option label="定时给药" value="SCHEDULED"/><el-option label="按需给药" value="PRN"/></el-select><el-select v-if="phase==='ADMINISTER'" v-model="timeliness" clearable placeholder="全部时效" style="width:150px" @change="resetPage"><el-option label="已逾期" value="OVERDUE"/><el-option label="30分钟内到期" value="DUE_30"/></el-select><el-select v-if="phase==='ADMINISTER'" v-model="dispenseStatus" clearable placeholder="全部发药状态" style="width:170px" @change="resetPage"><el-option label="未发药" value="NOT_DISPENSED"/><el-option label="已发药但余量不足" value="INSUFFICIENT"/><el-option label="可给药" value="READY"/></el-select><el-select v-if="canFilterDepartments" v-model="departmentId" clearable placeholder="全部科室" style="width:150px" @change="resetPage"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id"/></el-select><el-select v-model="sort" style="width:160px" @change="resetPage"><el-option label="计划时间：升序" value="PLANNED_AT_ASC"/><el-option label="计划时间：降序" value="PLANNED_AT_DESC"/></el-select><el-date-picker v-model="scheduledRange" class="schedule-range" style="width:330px;flex:0 0 330px" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="计划开始" end-placeholder="计划结束" @change="resetPage"/></div>
          <el-table v-loading="loading" :data="tasks">
            <el-table-column prop="patient_name" label="患者" min-width="100"/><el-table-column prop="department_name" label="科室" min-width="100"/><el-table-column prop="order_no" label="医嘱号" min-width="180" show-overflow-tooltip/>
            <el-table-column label="项目 / 执行要求" min-width="280"><template #default="{row}"><div v-if="row.group_name"><strong>配液组：{{ row.group_name }}</strong></div><div v-for="line in members(row)" :key="line.schedule_id || line.order_item_id"><strong>{{ line.item_name }}</strong><span class="muted"> · {{ enumLabel(medicalItemTypeLabels,line.item_type) }} · {{ enumLabel(orderTypeLabels,line.order_type) }}</span><div>{{ line.task_quantity }} {{ line.task_unit ?? line.unit }}<template v-if="line.route"> · {{ line.route }}</template></div><div>{{ line.instructions }}</div><MedicationProgress :line="line"/></div></template></el-table-column><el-table-column label="计划 / 生效时间" width="180"><template #default="{row}"><div>{{ formatDateTime(row.task_time) }}</div><el-tag v-if="row.timeliness==='OVERDUE'" type="danger" size="small">逾期 {{ row.overdue_minutes }} 分钟</el-tag><div v-else-if="row.preview_only" class="muted">{{ Math.max(0, Math.ceil((new Date(row.task_time).getTime()-Date.now())/60000)) }} 分钟后</div></template></el-table-column>
            <el-table-column v-if="phase==='ADMINISTER'" label="已发 / 已给" min-width="140"><template #default="{row}"><div v-for="line in members(row)" :key="line.id">{{ line.dispensed_quantity }} {{ line.unit }} / {{ line.executed_quantity }} {{ line.dose_unit }}</div></template></el-table-column>
            <el-table-column label="操作" width="170"><template #default="{row}"><el-button type="primary" :disabled="busy || loading || row.preview_only" @click="execute(row)">{{ row.preview_only ? '预览不可执行' : phase==='DISPENSE' ? '确认发药' : phase==='ADMINISTER' ? '确认给药' : '确认执行' }}</el-button><el-button v-if="['ADMINISTER','PERFORM'].includes(phase) && !row.preview_only" link :disabled="busy || loading" @click="execute(row,true)">未执行</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" @current-change="load"/>
        </el-tab-pane>
        <el-tab-pane v-if="auth.can('MEDICATION_ADMINISTER')" name="future" label="未来任务预览"><div class="toolbar task-filters"><span class="muted">仅供排班参考，不能提前执行。</span><el-radio-group v-model="futureHours" class="preview-window" @change="resetPage"><el-radio-button :value="2">未来2小时</el-radio-button><el-radio-button :value="4">未来4小时</el-radio-button><el-radio-button :value="8">未来8小时</el-radio-button></el-radio-group></div><div class="toolbar task-filters"><el-input v-model="keyword" clearable placeholder="患者 / 医嘱 / 床位" style="width:230px" @change="resetPage"/><el-select v-model="administrationMode" clearable placeholder="全部给药方式" style="width:150px" @change="resetPage"><el-option label="定时给药" value="SCHEDULED"/><el-option label="按需给药" value="PRN"/></el-select><el-select v-model="sort" style="width:160px" @change="resetPage"><el-option label="计划时间：升序" value="PLANNED_AT_ASC"/><el-option label="计划时间：降序" value="PLANNED_AT_DESC"/></el-select></div><el-table v-loading="loading" :data="tasks"><el-table-column prop="patient_name" label="患者"/><el-table-column prop="department_name" label="科室"/><el-table-column prop="order_no" label="医嘱号" min-width="180"/><el-table-column prop="item_name" label="项目" min-width="180"/><el-table-column label="计划时间 / 倒计时" width="210"><template #default="{row}">{{ formatDateTime(row.task_time) }}<div class="muted">{{ Math.max(0, Math.ceil((new Date(row.task_time).getTime()-Date.now())/60000)) }} 分钟后</div></template></el-table-column><el-table-column label="状态"><template #default="{row}"><el-tag type="info">预览，不可执行</el-tag></template></el-table-column></el-table><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next" @current-change="load"/></el-tab-pane>
        <el-tab-pane name="history" label="执行历史 / 纠错退药">
          <div style="max-width:420px;margin-bottom:16px"><AdmissionPicker v-model="admissionId" :disabled="busy"/></div>
          <el-table :data="history"><el-table-column label="来源医嘱号" min-width="190" show-overflow-tooltip><template #default="{row}">{{ row.order_no || '无关联医嘱' }}</template></el-table-column><el-table-column prop="item_name" label="项目" min-width="140"/><el-table-column label="环节" min-width="120"><template #default="{row}">{{ phases[row.phase] || '历史执行' }}</template></el-table-column><el-table-column label="数量" min-width="110"><template #default="{row}">{{ executionQuantity(row) }}</template></el-table-column><el-table-column prop="executor_name" label="执行人" min-width="110" show-overflow-tooltip/><el-table-column label="时间" width="170"><template #default="{row}">{{ formatDateTime(row.execution_time) }}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{ row.is_reversed ? '原记录已冲正' : row.status==='REVERSED' ? '冲正记录' : row.status==='NOT_DONE' ? '未执行' : row.status==='SUCCESS' ? '成功' : '失败' }}</template></el-table-column><el-table-column prop="result_note" label="结果 / 原因" min-width="130"/><el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button v-if="canReverse(row)" link type="danger" :disabled="busy" @click="reverse(row)">纠错 / 退药</el-button></template></el-table-column></el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>
<style scoped>
.phase-toolbar {
  padding: 6px 4px 2px;
  margin-bottom: 14px;
  overflow: visible;
}
.task-filters {
  gap: 8px;
  margin-bottom: 14px;
}
.task-filters :deep(.el-input__wrapper),
.task-filters :deep(.el-select__wrapper) {
  min-height: 32px;
  box-sizing: border-box;
}
.task-filters :deep(.el-input__inner),
.task-filters :deep(.el-select__placeholder),
.task-filters :deep(.el-range-input) {
  font-size: 13px;
}
.schedule-range {
  width: 330px;
  flex: 0 0 330px;
}
.schedule-range :deep(.el-range-input) {
  min-width: 0;
}
.schedule-range :deep(.el-range-separator) {
  width: 20px;
}
.preview-window {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 3px;
  overflow: visible;
}
.preview-window :deep(.el-radio-button__inner) {
  display: flex;
  align-items: center;
  min-height: 30px;
  padding: 0 12px;
  line-height: 1.2;
}
.phase-switch {
  padding: 3px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  background: var(--el-fill-color-light);
  box-shadow: 0 1px 2px rgb(15 55 52 / 6%);
}
.phase-switch :deep(.el-radio-button__inner) {
  min-width: 88px;
  border: 0;
  border-radius: 6px;
  box-shadow: none;
}
.phase-switch :deep(.el-radio-button:first-child .el-radio-button__inner),
.phase-switch :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 6px;
}
.phase-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  box-shadow: 0 1px 4px rgb(64 158 255 / 22%);
}
</style>
