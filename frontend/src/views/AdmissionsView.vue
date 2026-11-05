<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { PageResult, Row } from '@/types/api'
import { admissionStatusLabels, enumLabel } from '@/utils/labels'
import AdmissionCreateView from '@/views/AdmissionCreateView.vue'

const auth = useAuthStore()
const route = useRoute()
const loading = ref(false)
const registrationOpen = ref(false)
const keyword = ref('')
const departmentId = ref<number>()
const status = ref(typeof route.query.status === 'string' ? route.query.status : '')
const initialLongStayDays = typeof route.query.longStayDays === 'string' ? Number(route.query.longStayDays) : 14
const specialFilter = ref(route.query.settlementBlocked === 'true' ? 'BLOCKED' : route.query.longStayDays ? 'LONG_STAY' : '')
const departments = ref<Row[]>([])
const data = ref<PageResult<Row>>({ items: [], total: 0, page: 1, size: 20 })
async function load(page = 1) {
  loading.value = true
  try { data.value = await getData('/admissions', {
    keyword: keyword.value, departmentId: departmentId.value, status: status.value,
    longStayDays: specialFilter.value === 'LONG_STAY' ? initialLongStayDays : undefined,
    settlementBlocked: specialFilter.value === 'BLOCKED' ? true : undefined,
    page, size: 20,
  }) }
  finally { loading.value = false }
}
async function admissionCreated() {
  registrationOpen.value = false
  await load()
}
onMounted(async () => {
  departments.value = await getData('/admin/departments')
  await load()
})
</script>

<template>
  <div>
    <div class="page-header"><div><h2>当前住院患者</h2><p>按患者、住院号或科室查询在院记录，查看主管医生、床位和住院状态。</p></div><el-button v-if="auth.can('ADMISSION_MANAGE')" type="primary" @click="registrationOpen = true">办理入院</el-button></div>
    <div class="panel">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="患者 / 住院号" clearable style="width:260px" @keyup.enter="load()" />
        <el-select v-model="departmentId" clearable placeholder="全部科室" style="width:180px"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" /></el-select>
        <el-select v-model="status" placeholder="全部当前状态" style="width:150px"><el-option label="全部当前状态" value=""/><el-option label="住院中" value="IN_HOSPITAL"/><el-option label="待出院" value="PENDING_DISCHARGE"/></el-select>
        <el-select v-model="specialFilter" placeholder="全部患者" style="width:180px"><el-option label="全部患者" value=""/><el-option :label="`长住院（≥${initialLongStayDays}天）`" value="LONG_STAY"/><el-option label="结算受阻" value="BLOCKED"/></el-select>
        <el-button @click="load()">筛选</el-button>
      </div>
      <el-table v-loading="loading" :data="data.items">
        <el-table-column prop="admission_no" label="住院号" width="160" />
        <el-table-column prop="patient_name" label="患者" width="110" />
        <el-table-column prop="department_name" label="科室" />
        <el-table-column prop="attending_doctor_name" label="主管医生" />
        <el-table-column label="床位"><template #default="{ row }">{{ row.ward_name }} / {{ row.room_no }}-{{ row.bed_no }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'PENDING_DISCHARGE' ? 'warning' : 'success'">{{ enumLabel(admissionStatusLabels, row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="$router.push(`/admissions/${row.admission_id}`)">综合详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination style="margin-top:18px;justify-content:flex-end" background layout="total,prev,pager,next" :total="data.total" :current-page="data.page" :page-size="20" @current-change="load" />
    </div>
    <AdmissionCreateView v-if="registrationOpen && auth.can('ADMISSION_MANAGE')" @cancel="registrationOpen = false" @created="admissionCreated" />
  </div>
</template>
