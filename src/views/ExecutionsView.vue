<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from '@/components/AdmissionPicker.vue'
import MedicationProgress from '@/components/MedicationProgress.vue'
import { useAuthStore } from '@/stores/auth'
import type { Row } from '@/types/api'
import { enumLabel, medicalItemTypeLabels, orderTypeLabels } from '@/utils/labels'
import { executionQuantity, nonDrugOrderCategories } from '@/utils/orders'
import { formatDateTime } from '@/utils/datetime'
const auth = useAuthStore()
const tasks = ref<Row[]>([]), history = ref<Row[]>([]), admissionId = ref<number>()
const phase = ref(auth.can('PHARMACY_EXECUTE') ? 'DISPENSE' : auth.can('MEDICATION_ADMINISTER') ? 'ADMINISTER' : 'PERFORM')
const itemType = ref(''), page = ref(1), loading = ref(false), busy = ref(false), tab = ref('pending')
const phases: Record<string,string> = { DISPENSE: '药房发药', ADMINISTER: '护士给药', PERFORM: '非药品项目执行' }
const requestKeys = new Map<string,string>()
const groupedTasks = computed(() => tasks.value.filter((row,index,all) => !row.group_id || all.findIndex(x => x.group_id===row.group_id)===index))
const visibleTasks = computed(() => groupedTasks.value.slice((page.value-1)*20,page.value*20))
const members = (row: Row) => row.group_id ? tasks.value.filter(x => x.group_id===row.group_id) : [row]
async function load() {
  loading.value = true
  try { tasks.value = await getData('/orders/tasks', { phase: phase.value, itemType: itemType.value, page: page.value, size: 20 }) }
  finally { loading.value = false }
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
    const description = members(row).map(r => r.item_name+' '+(r.task_quantity ?? r.quantity_per_execution)+' '+(r.task_unit ?? r.unit)+(phase.value==='ADMINISTER' ? `（${r.frequency_name || r.frequency_code || '按频次'}，已实际给药 ${Number(r.administration_count || 0)} 次）` : '')).join('；')
    let note = ''
    if (notDone) {
      const result = await ElMessageBox.prompt('记录本次未执行原因（拒绝、暂停、待核对等）；不计费、不扣库存，也不完成医嘱。', '登记未执行', { inputPattern: /\S/, inputErrorMessage: '请填写原因' })
      note = result.value.trim()
    } else {
      const impact = phase.value==='ADMINISTER' ? '仅登记实际给药，不重复扣药房库存或计费。' : phase.value==='DISPENSE' ? '扣减药房库存，并按收费属性生成药费。' : '记录项目执行；仅收费项目产生费用。'
      await ElMessageBox.confirm('患者 '+row.patient_name+'：'+description+'。'+impact+(row.group_id ? '整组项目同时确认。' : ''), phases[phase.value], { type: 'warning' })
    }
    if (!requestKeys.has(key)) requestKeys.set(key, crypto.randomUUID())
    await postData('/orders/executions', { requestNo: requestKeys.get(key), orderItemId: row.order_item_id, phase: phase.value, notDone, note })
    requestKeys.delete(key); ElMessage.success(notDone ? '未执行原因已记录' : '本次操作已记录'); await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') await load() }
  finally { busy.value = false }
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
onMounted(load)
</script>
<template>
  <div><div class="page-header"><div><h2>医嘱执行</h2><p>药品按频次和首次给药时间到点生成任务；护士每次确认实际给药。发药数量是库存批量，不决定给药次数。</p></div></div>
    <div class="panel">
      <el-tabs v-model="tab">
        <el-tab-pane name="pending" label="待执行任务">
          <div class="toolbar">
            <el-radio-group v-model="phase" :disabled="busy" @change="page=1;itemType='';load()">
              <el-radio-button v-if="auth.can('PHARMACY_EXECUTE')" value="DISPENSE">药房发药</el-radio-button><el-radio-button v-if="auth.can('MEDICATION_ADMINISTER')" value="ADMINISTER">护士给药</el-radio-button><el-radio-button v-if="auth.canAny('MEDICATION_ADMINISTER','ORDER_WRITE')" value="PERFORM">非药品执行</el-radio-button>
            </el-radio-group>
            <el-select v-if="phase==='PERFORM'" v-model="itemType" clearable placeholder="全部非药品类别" style="width:170px" @change="page=1;load()"><el-option v-for="(label,key) in nonDrugOrderCategories" :key="key" :label="label" :value="key"/></el-select>
          </div>
          <el-table v-loading="loading" :data="visibleTasks">
            <el-table-column prop="patient_name" label="患者" min-width="100"/><el-table-column prop="department_name" label="科室" min-width="100"/><el-table-column prop="order_no" label="医嘱号" min-width="180" show-overflow-tooltip/>
            <el-table-column label="项目 / 执行要求" min-width="280"><template #default="{row}"><div v-if="row.group_name"><strong>配液组：{{ row.group_name }}</strong></div><div v-for="line in members(row)" :key="line.order_item_id"><strong>{{ line.item_name }}</strong><span class="muted"> · {{ enumLabel(medicalItemTypeLabels,line.item_type) }} · {{ enumLabel(orderTypeLabels,line.order_type) }}</span><div>{{ line.task_quantity ?? line.quantity_per_execution }} {{ line.task_unit ?? line.unit }}<template v-if="line.route"> · {{ line.route }}</template></div><div>{{ line.instructions }}</div><MedicationProgress :line="line"/></div></template></el-table-column>
            <el-table-column v-if="phase==='ADMINISTER'" label="已发 / 已给" min-width="140"><template #default="{row}"><div v-for="line in members(row)" :key="line.id">{{ line.dispensed_quantity }} {{ line.unit }} / {{ line.administered_quantity }} {{ line.dose_unit }}</div></template></el-table-column>
            <el-table-column label="操作" width="170"><template #default="{row}"><el-button type="primary" :disabled="busy || loading" @click="execute(row)">{{ phase==='DISPENSE' ? '确认发药' : phase==='ADMINISTER' ? '确认给药' : '确认执行' }}</el-button><el-button v-if="['ADMINISTER','PERFORM'].includes(phase)" link :disabled="busy || loading" @click="execute(row,true)">未执行</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" :total="groupedTasks.length" :page-size="20" layout="total,prev,pager,next"/>
        </el-tab-pane>
        <el-tab-pane name="history" label="执行历史 / 纠错退药">
          <div style="max-width:420px;margin-bottom:16px"><AdmissionPicker v-model="admissionId" :disabled="busy"/></div>
          <el-table :data="history"><el-table-column label="来源医嘱号" min-width="190" show-overflow-tooltip><template #default="{row}">{{ row.order_no || '无关联医嘱' }}</template></el-table-column><el-table-column prop="item_name" label="项目" min-width="140"/><el-table-column label="环节" min-width="120"><template #default="{row}">{{ phases[row.phase] || '历史执行' }}</template></el-table-column><el-table-column label="数量" min-width="110"><template #default="{row}">{{ executionQuantity(row) }}</template></el-table-column><el-table-column prop="executor_name" label="执行人" min-width="110" show-overflow-tooltip/><el-table-column label="时间" width="170"><template #default="{row}">{{ formatDateTime(row.execution_time) }}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{ row.is_reversed ? '原记录已冲正' : row.status==='REVERSED' ? '冲正记录' : row.status==='NOT_DONE' ? '未执行' : row.status==='SUCCESS' ? '成功' : '失败' }}</template></el-table-column><el-table-column prop="result_note" label="结果 / 原因" min-width="130"/><el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button v-if="canReverse(row)" link type="danger" :disabled="busy" @click="reverse(row)">纠错 / 退药</el-button></template></el-table-column></el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>
