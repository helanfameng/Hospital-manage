<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import OrderComposer from '@/components/OrderComposer.vue'
import OrderCatalog from '@/components/OrderCatalog.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import MedicationProgress from '@/components/MedicationProgress.vue'
import type { Row } from '@/types/api'
import { enumLabel, medicalItemTypeLabels, orderStatusOptions, orderTypeLabels } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'
const auth = useAuthStore()
const data = ref<any>({ items: [] }), detail = ref<Row>(), status = ref(''), page = ref(1)
const saving = ref(false), dialog = ref(false), catalogOpen = ref(false), detailOpen = ref(false)
const filters = reactive({ patientName: '', orderNo: '' })
const applied = ref({ status: '', patientName: '', orderNo: '' })
const loading = ref(false)
let requestVersion = 0
async function load() {
  const version = ++requestVersion
  loading.value = true
  try {
    const result = await getData('/orders', { ...applied.value, page: page.value, size: 20 })
    if (version === requestVersion) data.value = result
  } finally { if (version === requestVersion) loading.value = false }
}
async function search() {
  applied.value = { status: status.value, patientName: filters.patientName.trim(), orderNo: filters.orderNo.trim() }
  page.value = 1
  await load()
}
async function resetFilters() {
  filters.patientName = ''; filters.orderNo = ''; status.value = ''
  await search()
}
async function showDetail(row: Row) { detail.value = await getData('/orders/'+row.id); detailOpen.value = true }
async function created() { dialog.value = false; await load() }
async function action(row: Row, name: string, item?: Row) {
  if (saving.value) return
  saving.value = true
  try {
    await ElMessageBox.confirm(item?.group_id ? '此操作将同时变更该配液组的所有项目，确认继续？' : '确认变更此医嘱的状态？', '医嘱状态确认', { type: 'warning' })
    await postData(item ? `/orders/${row.id}/items/${item.id}/${name}` : `/orders/${row.id}/${name}`)
    ElMessage.success('医嘱状态已更新'); await load()
    if (detailOpen.value) await showDetail(row)
  } catch (error) { if (error !== 'cancel' && error !== 'close') console.error(error) }
  finally { saving.value = false }
}
const unused = (line: Row) => !Number(line.executed_quantity) && !Number(line.dispensed_quantity)
onMounted(load)
</script>
<template>
  <div>
    <div class="page-header"><div><h2>医嘱管理</h2><p>按项目开立用药、检查检验、治疗、护理、饮食及其他指令，查看执行与状态。</p></div><div><el-button v-if="auth.can('ADMIN_MANAGE')" @click="catalogOpen=true">项目资料维护</el-button><el-button v-if="auth.can('ORDER_WRITE')" type="primary" @click="dialog=true">开立医嘱</el-button></div></div>
    <div class="panel">
      <div class="toolbar order-filters">
        <el-select v-model="status" clearable aria-label="医嘱状态" placeholder="全部状态" style="width:170px" @change="search"><el-option v-for="s in orderStatusOptions" :key="s.value" :label="s.label" :value="s.value" /></el-select>
        <el-input v-model="filters.patientName" clearable aria-label="筛选患者姓名" placeholder="患者姓名（支持部分匹配）" :maxlength="120" style="width:240px" @keyup.enter="search" @clear="search"/>
        <el-input v-model="filters.orderNo" clearable aria-label="筛选医嘱号" placeholder="医嘱号（支持部分匹配）" :maxlength="32" style="width:260px" @keyup.enter="search" @clear="search"/>
        <el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button @click="resetFilters">重置筛选</el-button>
      </div>
      <el-table v-loading="loading" :data="data.items">
        <el-table-column prop="order_no" label="医嘱号" min-width="210" show-overflow-tooltip/><el-table-column prop="patient_name" label="患者" min-width="100"/>
        <el-table-column prop="prescriber_name" label="开立医生" min-width="100"/><el-table-column prop="item_count" label="项目" width="70"/>
        <el-table-column label="状态" width="100"><template #default="{ row }"><OrderStatusTag :status="row.status"/></template></el-table-column>
        <el-table-column label="操作" width="210"><template #default="{ row }"><el-button link type="primary" @click="showDetail(row)">详情</el-button><el-button v-if="auth.can('ORDER_WRITE') && row.status==='DRAFT'" link type="primary" :disabled="saving" @click="action(row,'submit')">提交</el-button><el-button v-if="auth.can('ORDER_WRITE') && row.status==='DRAFT'" link type="danger" :disabled="saving" @click="action(row,'cancel')">撤销</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :total="data.total || 0" :page-size="20" layout="total,prev,pager,next" @current-change="load"/>
    </div>
    <OrderComposer v-if="dialog" @cancel="dialog=false" @created="created"/>
    <OrderCatalog v-if="catalogOpen" @close="catalogOpen=false"/>
    <el-dialog v-model="detailOpen" title="医嘱详情" width="1100px" style="max-width:calc(100vw - 32px)">
      <template v-if="detail">
        <p>{{ detail.patient_name }} · {{ detail.order_no }} · 开立：{{ detail.prescriber_name }}</p>
        <el-table :data="detail.items" max-height="480">
          <el-table-column label="项目" min-width="210"><template #default="{row}"><strong>{{ row.item_name }}</strong><div class="muted">{{ enumLabel(medicalItemTypeLabels,row.item_type) }}<template v-if="row.group_name"> · 配液组：{{ row.group_name }}</template></div><div>{{ row.instructions }}</div></template></el-table-column>
          <el-table-column label="执行要求" min-width="210"><template #default="{row}"><template v-if="row.item_type==='DRUG'"><div>单次用量：{{ row.dose_per_administration }} {{ row.dose_unit }}</div><div>{{ row.route }}</div><div>建议发药：{{ row.requested_dispense_quantity }} {{ row.unit }}</div><div v-if="row.order_type==='LONG_TERM'">{{ row.administration_mode==='PRN' ? '按需' : '定时' }}，最小间隔 {{ row.administration_interval_minutes }} 分钟</div></template><template v-else>每次执行：{{ row.execution_quantity_per_operation }} {{ row.unit }}</template><div>{{ row.billable ? '收费项目' : '不收费' }}</div></template></el-table-column>
          <el-table-column label="类型 / 有效期" min-width="180"><template #default="{row}"><div>{{ enumLabel(orderTypeLabels,row.order_type) }}</div><div>{{ formatDateTime(row.effective_start_time) }} 起</div><div v-if="row.effective_end_time">{{ formatDateTime(row.effective_end_time) }} 止</div></template></el-table-column>
          <el-table-column label="进展" min-width="160"><template #default="{row}"><OrderStatusTag :status="row.item_status"/><template v-if="row.item_type==='DRUG'"><div>已发：{{ row.dispensed_quantity }} {{ row.unit }}</div><div>已给：{{ row.executed_quantity }} {{ row.dose_unit }}</div><MedicationProgress :line="row"/></template><div v-else>已执行：{{ row.executed_quantity }} {{ row.unit }}</div></template></el-table-column>
          <el-table-column v-if="auth.can('ORDER_WRITE')" label="项目操作" width="160"><template #default="{row}">
            <el-button v-if="row.item_status==='DRAFT'" link type="primary" :disabled="saving" @click="action(detail,'submit',row)">提交</el-button>
            <el-button v-if="['DRAFT','SUBMITTED','IN_PROGRESS'].includes(row.item_status) && unused(row)" link type="danger" :disabled="saving" @click="action(detail,'cancel',row)">撤销</el-button>
            <template v-if="['SUBMITTED','IN_PROGRESS'].includes(row.item_status) && row.order_type==='LONG_TERM'"><el-button link type="warning" :disabled="saving" @click="action(detail,'stop',row)">停止</el-button><el-button v-if="Number(row.executed_quantity)>0" link type="success" :disabled="saving" @click="action(detail,'complete',row)">完成</el-button></template>
          </template></el-table-column>
        </el-table>
        <p v-if="detail.note">备注：{{ detail.note }}</p>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.order-filters { flex-wrap: wrap; }
</style>
