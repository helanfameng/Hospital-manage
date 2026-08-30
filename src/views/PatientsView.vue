<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getData, postData, putData } from '@/api/client'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { admissionStatusLabels, enumLabel } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'
import type { PageResult, Row } from '@/types/api'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const editingId = ref<number>()
const historyDialog = ref(false)
const patientHistory = ref<Row[]>([])
const keyword = ref('')
const data = ref<PageResult<Row>>({ items: [], total: 0, page: 1, size: 20 })
const dialog = ref(false)
const form = reactive({
  name: '', gender: 'MALE', birthDate: '', identityNumber: '', phone: '', address: '',
  emergencyContact: '', emergencyPhone: '', active: true,
})

async function load(page = 1) {
  loading.value = true
  try { data.value = await getData('/patients', { keyword: keyword.value, page, size: 20 }) }
  finally { loading.value = false }
}
async function create() {
  if (saving.value) return
  saving.value = true
  try {
    if (editingId.value) await putData(`/patients/${editingId.value}`, form)
    else await postData('/patients', form)
    ElMessage.success('患者资料已保存'); dialog.value = false; await load()
  } finally { saving.value = false }
}
function openNew() {
  editingId.value = undefined
  Object.assign(form,{name:'',gender:'MALE',birthDate:'',identityNumber:'',phone:'',address:'',emergencyContact:'',emergencyPhone:'',active:true})
  dialog.value = true
}
async function editPatient(row: Row) {
  const p = await getData<Row>(`/patients/${row.id}`)
  editingId.value = row.id
  Object.assign(form,{name:p.name,gender:p.gender,birthDate:p.birth_date,identityNumber:p.identity_number,phone:p.phone || '',address:p.address || '',emergencyContact:p.emergency_contact || '',emergencyPhone:p.emergency_phone || '',active:Boolean(p.active)})
  dialog.value = true
}
async function showHistory(row: Row) {
  patientHistory.value = (await getData<Row>(`/patients/${row.id}`)).admissions
  historyDialog.value = true
}
onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-header">
      <div><h2>患者档案</h2><p>按姓名、患者号或证件号查询患者信息，也可为新患者建立档案。</p></div>
      <el-button v-if="auth.can('PATIENT_WRITE')" type="primary" @click="openNew">新建患者</el-button>
    </div>
    <div class="panel">
      <div class="toolbar"><el-input v-model="keyword" clearable placeholder="姓名 / 患者号 / 证件号" style="width:310px" @keyup.enter="load()" /><el-button @click="load()">查询</el-button></div>
      <el-table v-loading="loading" :data="data.items">
        <el-table-column prop="patient_no" label="患者号" width="130" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="性别" width="80"><template #default="{ row }">{{ row.gender === 'MALE' ? '男' : row.gender === 'FEMALE' ? '女' : '其他' }}</template></el-table-column>
        <el-table-column prop="birth_date" label="出生日期" width="130" />
        <el-table-column prop="identity_number" label="证件号" min-width="180" />
        <el-table-column prop="phone" label="联系电话" width="140" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'">{{ row.active ? '有效' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="{row}"><el-button link @click="showHistory(row)">住院历史</el-button><el-button v-if="auth.can('PATIENT_WRITE')" link type="primary" @click="editPatient(row)">修改</el-button></template></el-table-column>
      </el-table>
      <el-pagination style="margin-top:18px;justify-content:flex-end" background layout="total, prev, pager, next" :total="data.total" :page-size="20" @current-change="load" />
    </div>
    <el-dialog v-model="dialog" :title="editingId ? '修改患者档案' : '新建患者档案'" width="680px">
      <el-form label-position="top">
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item></el-col><el-col :span="12"><el-form-item label="性别"><el-select v-model="form.gender" class="full-width"><el-option label="男" value="MALE" /><el-option label="女" value="FEMALE" /><el-option label="其他" value="OTHER" /></el-select></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" value-format="YYYY-MM-DD" class="full-width" /></el-form-item></el-col><el-col :span="12"><el-form-item label="证件号"><el-input v-model="form.identityNumber" :disabled="Boolean(editingId)" /></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item></el-col><el-col :span="12"><el-form-item label="住址"><el-input v-model="form.address" /></el-form-item></el-col></el-row>
        <el-row :gutter="16"><el-col :span="12"><el-form-item label="紧急联系人"><el-input v-model="form.emergencyContact" /></el-form-item></el-col><el-col :span="12"><el-form-item label="紧急联系电话"><el-input v-model="form.emergencyPhone" /></el-form-item></el-col></el-row>
        <el-form-item v-if="editingId" label="档案有效"><el-switch v-model="form.active"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.name || !form.identityNumber || !form.birthDate" @click="create">保存患者</el-button></template>
    </el-dialog>
    <el-dialog v-model="historyDialog" title="患者住院历史" width="800px"><el-table :data="patientHistory"><el-table-column prop="admission_no" label="住院号"/><el-table-column label="入院时间"><template #default="{row}">{{ formatDateTime(row.admission_time) }}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{ enumLabel(admissionStatusLabels,row.status) }}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button link type="primary" @click="$router.push(`/admissions/${row.id}`)">综合详情</el-button></template></el-table-column></el-table></el-dialog>
  </div>
</template>
