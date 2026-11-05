<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { Row } from '@/types/api'
import { formatDateTime } from '@/utils/datetime'

const auth = useAuthStore()
const beds = ref<Row[]>([])
const admissions = ref<any>({ items: [] })
const alerts = ref<any>({ items: [] })
const loading = ref(true)

const canViewPatients = computed(() => auth.can('PATIENT_READ'))
const canViewAlerts = computed(() => auth.canAny('DEPOSIT_MANAGE', 'SETTLEMENT_MANAGE'))
const occupied = computed(() => beds.value.filter((b) => b.active && b.status === 'OCCUPIED').length)
const available = computed(() => beds.value.filter((b) => b.active && b.status === 'AVAILABLE').length)
const hasDashboardData = computed(() => canViewPatients.value || canViewAlerts.value)

onMounted(async () => {
  try {
    const tasks = [
      canViewPatients.value ? getData<Row[]>('/beds') : Promise.resolve([]),
      canViewPatients.value ? getData<any>('/admissions', { size: 8 }) : Promise.resolve({ items: [], total: 0 }),
      canViewAlerts.value ? getData<any>('/alerts', { status: 'OPEN', size: 6 }) : Promise.resolve({ items: [], total: 0 }),
    ]
    ;[beds.value, admissions.value, alerts.value] = await Promise.all(tasks)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2>今日运行概览</h2>
        <p>{{ canViewAlerts ? '查看当前在院人数、床位使用情况和待处理的欠费提醒。' : '查看当前在院人数和床位使用情况。' }}</p>
      </div>
    </div>
    <div v-if="hasDashboardData" class="metric-grid dashboard-grid">
      <div v-if="canViewPatients" class="metric-card"><span class="label">当前在院</span><div class="value">{{ admissions.total || admissions.items.length }}</div><div class="hint">含待出院患者</div></div>
      <div v-if="canViewPatients" class="metric-card"><span class="label">床位占用</span><div class="value">{{ occupied }}</div><div class="hint">空闲 {{ available }} 张</div></div>
      <div v-if="canViewAlerts" class="metric-card"><span class="label">开放欠费提醒</span><div class="value">{{ alerts.total || alerts.items.length }}</div><div class="hint">优先处理严重提醒</div></div>
      <div v-if="canViewPatients" class="metric-card"><span class="label">床位使用率</span><div class="value">{{ occupied + available ? Number((occupied / (occupied + available) * 100).toFixed(2)) : 0 }}%</div><div class="hint">按当前可用床位计算</div></div>
    </div>
    <el-empty v-else description="当前角色暂无可查看的工作台数据" />
    <div v-if="hasDashboardData" class="two-columns" :class="{ 'single-column': !canViewAlerts || !canViewPatients }">
      <div v-if="canViewPatients" class="panel">
        <div class="page-header"><div><h2>最新住院患者</h2><p>查看最近办理入院的患者、科室和床位信息。</p></div><el-button text @click="$router.push('/admissions')">查看全部</el-button></div>
        <el-table :data="admissions.items">
          <el-table-column prop="admission_no" label="住院号" width="160" />
          <el-table-column prop="patient_name" label="患者" />
          <el-table-column prop="department_name" label="科室" />
          <el-table-column prop="bed_no" label="床位" width="80" />
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click="$router.push(`/admissions/${row.admission_id}`)">详情</el-button></template></el-table-column>
        </el-table>
      </div>
      <div v-if="canViewAlerts" class="panel">
        <div class="page-header"><div><h2>风险提醒</h2><p>查看需要及时跟进的患者欠费提醒和当前可用余额。</p></div></div>
        <el-empty v-if="!alerts.items.length" description="当前没有开放提醒" :image-size="80" />
        <el-timeline v-else>
          <el-timeline-item v-for="item in alerts.items" :key="item.id" :type="item.level === 'CRITICAL' ? 'danger' : 'warning'" :timestamp="formatDateTime(item.created_at)">
            <strong>{{ item.patient_name }}</strong> · 可用余额 ¥{{ item.balance_snapshot }}
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>
  </div>
</template>
