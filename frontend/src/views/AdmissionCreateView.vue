<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from '@/components/AdmissionPicker.vue'
import type { Row } from '@/types/api'

const emit = defineEmits<{ cancel: []; created: [] }>()
const loading = ref(false)
const initializing = ref(true)
const ready = ref(false)
const departments = ref<Row[]>([])
const doctors = ref<Row[]>([])
const beds = ref<Row[]>([])
const form = reactive({ patientId: undefined as number | undefined, departmentId: undefined as number | undefined, doctorId: undefined as number | undefined, bedId: undefined as number | undefined, initialDeposit: 3000 })
const departmentBeds = computed(() => beds.value.filter((b) => b.department_id === form.departmentId))
const canSubmit = computed(() => ready.value && !loading.value && Boolean(form.patientId && form.departmentId && form.doctorId && form.bedId)
  && Number.isFinite(form.initialDeposit) && form.initialDeposit >= 0)

function cancel() {
  if (!loading.value) emit('cancel')
}

watch(() => form.departmentId, async (value) => {
  form.doctorId = undefined
  form.bedId = undefined
  doctors.value = []
  const result = value ? await getData<Row[]>('/admin/staff', { staffType: 'DOCTOR', departmentId: value }) : []
  if (value === form.departmentId) doctors.value = result.filter(d => d.active)
})

async function submit() {
  if (!canSubmit.value) return
  loading.value = true
  try {
    await postData('/admissions', { ...form })
    ElMessage.success('入院、押金与初次分床已在同一事务中完成')
    emit('created')
  } catch {
    // The API client displays the error; keep this form open for correction.
  } finally { loading.value = false }
}

onMounted(async () => {
  try {
    const [dept, availableBeds] = await Promise.all([
      getData<Row[]>('/admin/departments'),
      getData<Row[]>('/beds', { status: 'AVAILABLE' }),
    ])
    departments.value = dept.filter(d => d.active)
    beds.value = availableBeds.filter(b => b.active)
    ready.value = true
  } catch {
    // Do not allow submission with incomplete registration options.
  } finally { initializing.value = false }
})
</script>

<template>
  <el-dialog :model-value="true" title="办理入院" width="720px" style="max-width:calc(100vw - 32px)"
    :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading" @close="cancel">
    <p class="muted" style="margin:0 0 20px">选择已建档患者，为其安排科室、主管医生和床位，并登记初始押金。</p>
    <div v-loading="initializing">
      <el-alert v-if="!initializing && !ready" title="登记选项加载失败，请关闭窗口后重试" type="error" :closable="false" style="margin-bottom:16px" />
      <el-alert title="事务保护" description="系统会锁定目标床位并检查患者是否已有有效住院记录。" type="info" :closable="false" style="margin-bottom:22px" />
      <el-form label-position="top" :disabled="loading || initializing || !ready">
        <el-row :gutter="20">
          <el-col :span="12"><el-form-item label="患者"><AdmissionPicker v-model="form.patientId" kind="patient" :disabled="loading || initializing || !ready" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="入院科室"><el-select v-model="form.departmentId" class="full-width"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" /></el-select></el-form-item></el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12"><el-form-item label="主管医生"><el-select v-model="form.doctorId" class="full-width"><el-option v-for="d in doctors" :key="d.id" :label="d.name" :value="d.id" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="初始床位"><el-select v-model="form.bedId" class="full-width"><el-option v-for="b in departmentBeds" :key="b.id" :label="`${b.ward_name} / ${b.room_no}-${b.bed_no}`" :value="b.id" /></el-select></el-form-item></el-col>
        </el-row>
        <el-form-item label="初始押金（元）"><el-input-number v-model="form.initialDeposit" :min="0" :precision="2" :step="500" style="width:260px" /></el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button :disabled="loading" @click="cancel">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="submit">确认办理入院</el-button>
    </template>
  </el-dialog>
</template>
