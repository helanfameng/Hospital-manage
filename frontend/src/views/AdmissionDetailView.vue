<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { Row } from '@/types/api'
import { admissionStatusLabels, bedEndReasonLabels, depositTypeLabels, enumLabel, orderStatusLabels } from '@/utils/labels'
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
const availableBeds = ref<Row[]>([])
const transferDialog = ref(false)
const transfer = reactive({ targetBedId: undefined as number | undefined, targetDoctorId: undefined as number | undefined, reason: '' })
const balance = computed(() => Number(detail.value.available_balance || 0))

const targetDepartment = computed(() => availableBeds.value.find(b => b.id === transfer.targetBedId)?.department_id)
const targetDoctors = computed(() => doctors.value.filter(d => d.active && d.department_id === targetDepartment.value))
const crossDepartment = computed(() => targetDepartment.value && targetDepartment.value !== detail.value.department_id)
watch(() => transfer.targetBedId, () => { transfer.targetDoctorId = undefined })
async function load() {
  const current = id.value
  const values = await Promise.all([
    getData<Row>(`/admissions/${current}`), getData<any>('/orders', { admissionId: current, page: page.value, size: 20 }),
    getData<Row[]>('/deposits', { admissionId: current }), getData<Row[]>('/charges', { admissionId: current }),
  ])
  if (current !== id.value) return
  ;[detail.value, orders.value, deposits.value, charges.value] = values
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
watch(id, () => { detail.value = {}; orders.value = { items: [] }; deposits.value = []; charges.value = []; page.value = 1; void load() }, { immediate: true })
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
        <el-tab-pane label="医嘱"><el-table :data="orders.items"><el-table-column prop="order_no" label="医嘱号" /><el-table-column label="状态"><template #default="{ row }">{{ enumLabel(orderStatusLabels, row.status) }}</template></el-table-column><el-table-column prop="prescriber_name" label="开立医生" /><el-table-column prop="item_count" label="项目数" /></el-table><el-pagination v-model:current-page="page" :page-size="20" :total="orders.total || 0" layout="prev, pager, next, total" @current-change="load" /></el-tab-pane>
        <el-tab-pane label="费用"><el-table :data="charges"><el-table-column label="时间"><template #default="{ row }">{{ formatDateTime(row.charged_at) }}</template></el-table-column><el-table-column prop="item_name" label="项目" /><el-table-column prop="quantity" label="数量" /><el-table-column prop="amount" label="金额" /></el-table></el-tab-pane>
        <el-tab-pane label="押金"><el-table :data="deposits"><el-table-column label="时间"><template #default="{ row }">{{ formatDateTime(row.transaction_time) }}</template></el-table-column><el-table-column label="类型"><template #default="{ row }">{{ enumLabel(depositTypeLabels, row.transaction_type) }}</template></el-table-column><el-table-column prop="amount" label="金额" /><el-table-column prop="operator_name" label="操作人" /></el-table></el-tab-pane>
      </el-tabs>
    </div>
    <el-dialog v-model="transferDialog" title="办理转床" width="520px">
      <el-form label-position="top"><el-form-item label="目标床位"><el-select v-model="transfer.targetBedId" filterable class="full-width"><el-option v-for="b in availableBeds" :key="b.id" :value="b.id" :label="`${b.department_name} / ${b.ward_name} / ${b.room_no}-${b.bed_no}`" /></el-select></el-form-item><el-form-item :label="crossDepartment ? '目标科室主管医生（必选）' : '主管医生（不选则保持原医生）'"><el-select v-model="transfer.targetDoctorId" clearable class="full-width"><el-option v-for="d in targetDoctors" :key="d.id" :value="d.id" :label="d.name" /></el-select></el-form-item><el-form-item label="转床原因"><el-input v-model="transfer.reason" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="transferDialog=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!transfer.targetBedId || !transfer.reason.trim() || Boolean(crossDepartment && !transfer.targetDoctorId)" @click="submitTransfer">确认转床</el-button></template>
    </el-dialog>
  </div>
</template>
