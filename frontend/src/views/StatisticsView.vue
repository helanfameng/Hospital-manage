<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getData } from '@/api/client'
import EChart from '@/components/EChart.vue'
import type { Row } from '@/types/api'

type StatisticsData = {
  currentOperations: Row
  admissionTrend: Row[]
  departmentCensus: Row[]
  bedUtilization: Row[]
  lengthOfStayAndDebt: Row
  lengthOfStayDistribution: Row[]
}

const router = useRouter()
const loading = ref(false)
const range = ref<[string, string]>()
const data = ref<StatisticsData>({
  currentOperations: {}, admissionTrend: [], departmentCensus: [], bedUtilization: [],
  lengthOfStayAndDebt: {}, lengthOfStayDistribution: [],
})

function number(value: unknown) { return Number(value || 0) }
function formatMoney(value: unknown) {
  return number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function shortDate(value: unknown) {
  const text = String(value || '')
  return text.length >= 10 ? text.slice(5, 10) : text
}
async function load() {
  loading.value = true
  try { data.value = await getData('/statistics/overview', { from: range.value?.[0], to: range.value?.[1] }) }
  finally { loading.value = false }
}

const currentBedRate = computed(() => {
  const current = data.value.currentOperations
  const total = number(current.occupied_beds) + number(current.available_beds)
  return total ? Number((number(current.occupied_beds) / total * 100).toFixed(2)) : 0
})
const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' }, legend: { top: 0 },
  grid: { left: 48, right: 28, bottom: 36, top: 48 },
  xAxis: { type: 'category', boundaryGap: false, data: data.value.admissionTrend.map(x => shortDate(x.stat_date)) },
  yAxis: { type: 'value', name: '人数', minInterval: 1 },
  series: [
    { name: '每日在院', type: 'line', smooth: true, showSymbol: false, data: data.value.admissionTrend.map(x => number(x.inpatient_census)), lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 } },
    { name: '入院', type: 'line', showSymbol: false, data: data.value.admissionTrend.map(x => number(x.admitted_count)) },
    { name: '出院', type: 'line', showSymbol: false, data: data.value.admissionTrend.map(x => number(x.discharged_count)) },
  ],
}))
const bedOption = computed(() => ({
  tooltip: { trigger: 'axis', formatter: (params: any[]) => {
    const row = data.value.bedUtilization[params[0]?.dataIndex]
    return row ? `${row.department_name} · ${row.ward_name}<br/>占用 ${row.occupied_beds} 张 / 可周转 ${number(row.occupied_beds) + number(row.available_beds)} 张<br/>维修 ${number(row.maintenance_beds)} 张` : ''
  } },
  grid: { left: 45, right: 20, bottom: 58, top: 25 },
  xAxis: { type: 'category', data: data.value.bedUtilization.map(x => x.ward_name), axisLabel: { rotate: 25 } },
  yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
  series: [{ name: '当前占用率', type: 'bar', data: data.value.bedUtilization.map(x => number(x.utilization_rate)), itemStyle: { color: '#0f766e', borderRadius: [7, 7, 0, 0] } }],
}))
const stayDistributionOption = computed(() => {
  const buckets = ['0-3天', '4-7天', '8-14天', '15-30天', '30天以上']
  const counts = new Map(data.value.lengthOfStayDistribution.map(x => [x.stay_bucket, number(x.patient_count)]))
  return {
    tooltip: { trigger: 'axis' }, grid: { left: 64, right: 20, bottom: 38, top: 25 },
    xAxis: { type: 'category', data: buckets },
    yAxis: {
      type: 'value', minInterval: 1, name: '出\n院\n人\n数',
      nameLocation: 'middle', nameGap: 42, nameRotate: 0,
    },
    series: [{ name: '出院人数', type: 'bar', data: buckets.map(x => counts.get(x) || 0), itemStyle: { color: '#d97706', borderRadius: [7, 7, 0, 0] } }],
  }
})

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="statistics-page">
    <div class="page-header">
      <div><h2>住院运行统计</h2><p>汇总住院运行、床位使用与住院效率数据。</p></div>
      <div class="period-filter">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-button type="primary" @click="load">应用日期</el-button>
      </div>
    </div>

    <section class="statistics-section">
      <div class="section-heading"><div><span class="section-index">01</span><h3>当前运行</h3></div><p>实时快照，不受日期筛选影响</p></div>
      <div class="metric-grid operations-grid">
        <button class="metric-card metric-action" type="button" @click="router.push('/admissions')"><span class="label">当前在院</span><div class="value">{{ number(data.currentOperations.current_inpatients) }}</div><div class="hint">查看当前住院患者</div></button>
        <div class="metric-card"><span class="label">可用床位</span><div class="value">{{ number(data.currentOperations.available_beds) }}</div><div class="hint">当前占用率 {{ currentBedRate }}%</div></div>
        <div class="metric-card"><span class="label">待出院</span><div class="value">{{ number(data.currentOperations.pending_discharge_count) }}</div><div class="hint metric-links"><el-button link type="primary" @click="router.push('/admissions?status=PENDING_DISCHARGE')">查看全部</el-button><el-button link type="warning" @click="router.push('/admissions?status=PENDING_DISCHARGE&settlementBlocked=true')">受阻 {{ number(data.currentOperations.blocked_discharge_count) }} 人</el-button></div></div>
        <button class="metric-card metric-action" type="button" @click="router.push(`/admissions?longStayDays=${number(data.currentOperations.long_stay_days) || 14}`)"><span class="label">长住院患者</span><div class="value">{{ number(data.currentOperations.long_stay_count) }}</div><div class="hint">当前住院满 {{ number(data.currentOperations.long_stay_days) || 14 }} 天</div></button>
        <div class="metric-card risk-card"><span class="label">欠费患者</span><div class="value">{{ number(data.currentOperations.overdue_patient_count) }}</div><div class="hint">欠费合计 ¥{{ formatMoney(data.currentOperations.overdue_amount) }}</div></div>
        <div class="metric-card"><span class="label">维修床位</span><div class="value">{{ number(data.currentOperations.maintenance_beds) }}</div><div class="hint">不计入可周转床位分母</div></div>
      </div>
    </section>

    <section class="statistics-section">
      <div class="section-heading"><div><span class="section-index">02</span><h3>趋势分析</h3></div><p>按日展示住院流量与在院规模，未选择日期时默认近 6 个月</p></div>
      <div class="panel"><h3>每日在院、入院与出院趋势</h3><EChart :option="trendOption" /></div>
    </section>

    <section class="statistics-section">
      <div class="section-heading"><div><span class="section-index">03</span><h3>深入分析</h3></div><p>住院日按期间出院病例计算，避免遗漏跨期住院</p></div>
      <div class="metric-grid efficiency-grid">
        <div class="metric-card"><span class="label">平均住院日</span><div class="value">{{ number(data.lengthOfStayAndDebt.average_length_of_stay) }}</div><div class="hint">受极端长住院影响</div></div>
        <div class="metric-card"><span class="label">中位住院日</span><div class="value">{{ number(data.lengthOfStayAndDebt.median_length_of_stay) }}</div><div class="hint">更接近典型患者</div></div>
        <div class="metric-card"><span class="label">P90 住院日</span><div class="value">{{ number(data.lengthOfStayAndDebt.p90_length_of_stay) }}</div><div class="hint">识别长尾住院</div></div>
        <div class="metric-card"><span class="label">出院样本数</span><div class="value">{{ number(data.lengthOfStayAndDebt.discharged_sample_size) }}</div><div class="hint">所选期间完成出院</div></div>
      </div>
      <div class="two-columns statistics-charts">
        <div class="panel"><h3>住院日分布</h3><EChart :option="stayDistributionOption" /></div>
        <div class="panel"><h3>病区当前床位占用率</h3><EChart :option="bedOption" /></div>
      </div>
      <div class="panel department-panel">
        <h3>科室住院负荷</h3>
        <p class="muted metric-note">期间入院和出院按住院记录当前归属科室汇总；转科病例不代表原科室历史流量。</p>
        <el-table :data="data.departmentCensus"><el-table-column prop="department_name" label="科室"/><el-table-column prop="current_inpatients" label="当前在院"/><el-table-column prop="admitted_count" label="期间入院"/><el-table-column prop="discharged_count" label="期间出院"/></el-table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.statistics-page { display: flex; flex-direction: column; gap: 28px; }
.statistics-page > .page-header { margin-bottom: 0; }
.period-filter { display: flex; gap: 8px; }
.statistics-section { display: flex; flex-direction: column; gap: 16px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; }
.section-heading > div { display: flex; align-items: center; gap: 10px; }
.section-heading h3, .panel h3 { margin: 0; }
.section-heading p { margin: 0; color: #758986; font-size: 13px; }
.section-index { color: #0f766e; font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.operations-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); margin-bottom: 0; }
.efficiency-grid { margin-bottom: 0; }
.metric-action { text-align: left; cursor: pointer; font: inherit; transition: transform .16s, border-color .16s; }
.metric-action:hover { transform: translateY(-2px); border-color: #5bb7ae; }
.metric-action:focus-visible { outline: 3px solid rgba(15,118,110,.2); outline-offset: 2px; }
.risk-card .value { color: #b45309; }
.metric-links { display: flex; align-items: center; gap: 10px; }
.metric-links :deep(.el-button + .el-button) { margin-left: 0; }
.statistics-charts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.statistics-charts > .panel { min-width: 0; }
.department-panel { margin-top: 4px; }
.metric-note { margin: 6px 0 16px; font-size: 12px; }
@media (max-width: 1280px) { .operations-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
