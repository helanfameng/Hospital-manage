<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getData, putData } from '@/api/client'
import type { Row } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { shortWardName, useBedFilters } from '@/composables/useBedFilters'

const auth = useAuthStore()
const beds = ref<Row[]>([])
const loading = ref(false)
const { status, departmentId, wardName, departments, wards, filtered, hasFilters, resetFilters } = useBedFilters(beds)

async function load() {
  loading.value = true
  try { beds.value = await getData('/beds') } finally { loading.value = false }
}
async function changeStatus(bed: Row, next: string) {
  await ElMessageBox.confirm(`确认将 ${bed.room_no}-${bed.bed_no} 设置为${next === 'MAINTENANCE' ? '维修' : next === 'DISABLED' ? '停用' : '空闲'}？`, '床位状态确认', { type: 'warning' })
  await putData(`/beds/${bed.id}/status`, { status: next })
  await load()
}
onMounted(load)
</script>

<template>
  <div>
    <div class="page-header"><div><h2>病区床位地图</h2><p>按状态、科室和病区查看床位及在床患者，并维护空闲、维修或停用状态。</p></div></div>
    <div class="panel">
      <div class="toolbar">
        <el-radio-group v-model="status"><el-radio-button label="">全部</el-radio-button><el-radio-button label="AVAILABLE">空闲</el-radio-button><el-radio-button label="OCCUPIED">占用</el-radio-button><el-radio-button label="MAINTENANCE">维修</el-radio-button><el-radio-button label="DISABLED">停用</el-radio-button></el-radio-group>
        <span class="muted">共 {{ filtered.length }} 张</span>
        <div class="bed-location-filters">
          <div class="bed-location-filter">
            <span class="muted">科室</span>
            <el-select v-model="departmentId" aria-label="按科室筛选" clearable placeholder="全部科室" style="width:150px">
              <el-option v-for="department in departments" :key="department.id" :label="department.name" :value="department.id" />
            </el-select>
          </div>
          <div class="bed-location-filter">
            <span class="muted">病区</span>
            <el-select v-model="wardName" aria-label="按病区筛选" clearable placeholder="全部病区" style="width:190px">
              <el-option v-for="ward in wards" :key="ward" :label="ward" :value="ward" />
            </el-select>
          </div>
          <el-button :disabled="!hasFilters" @click="resetFilters">重置筛选</el-button>
        </div>
      </div>
      <div v-loading="loading" class="bed-grid">
        <div v-for="bed in filtered" :key="bed.id" class="bed-card" :class="bed.status">
          <div class="bed-card-header">
            <strong><span class="status-dot" :class="`status-${bed.status}`" />{{ bed.room_no }}-{{ bed.bed_no }}</strong>
            <el-dropdown v-if="!bed.patient_name && auth.can('ADMISSION_MANAGE')" class="bed-card-actions">
              <el-button size="small" text>调整状态<el-icon><ArrowDown /></el-icon></el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item @click="changeStatus(bed,'AVAILABLE')">设为空闲</el-dropdown-item><el-dropdown-item @click="changeStatus(bed,'MAINTENANCE')">设为维修</el-dropdown-item><el-dropdown-item @click="changeStatus(bed,'DISABLED')">设为停用</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </div>
          <small>{{ bed.department_name }} · {{ shortWardName(bed.ward_name, bed.department_name) }}</small>
          <p v-if="bed.patient_name" class="bed-patient">
            <span>{{ bed.patient_name }}</span>
            <span class="muted">{{ bed.admission_no }}</span>
          </p>
        </div>
      </div>
      <el-empty v-if="!loading && !filtered.length" description="没有符合筛选条件的床位" />
    </div>
  </div>
</template>

<style scoped>
.bed-location-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-left: auto; }
.bed-location-filter { display: flex; align-items: center; gap: 8px; }
.bed-patient { margin: 10px 0 0; overflow-wrap: anywhere; }
.bed-patient > span { display: block; }
</style>
