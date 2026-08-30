<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getData } from '@/api/client'
import EChart from '@/components/EChart.vue'
import type { Row } from '@/types/api'

const loading = ref(false)
const range = ref<[string, string]>()
const data = ref<any>({ departmentCensus: [], bedUtilization: [], revenueComposition: [], drugUsage: [], lengthOfStayAndDebt: {} })
const itemTypeLabels: Record<string, string> = {
  BED_FEE: '床位费',
  DRUG: '药品费',
  EXAMINATION: '检查费',
  LAB: '检验费',
  TREATMENT: '治疗费',
  NURSING: '护理费',
  DIET: '饮食费',
  OTHER: '其他费用',
}
function formatChartNumber(value: unknown) {
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}
function bedOccupancyRate(row: Row) {
  return Number(row.utilization_rate || 0)
}
async function load() {
  loading.value = true
  try { data.value = await getData('/statistics/overview', { from: range.value?.[0], to: range.value?.[1] }) }
  finally { loading.value = false }
}
const bedOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: (params: any[]) => {
      const row = data.value.bedUtilization[params[0]?.dataIndex]
      if (!row) return ''
      return `占用床位：${row.occupied_beds} 张<br/>可用床位总数：${Number(row.occupied_beds) + Number(row.available_beds)} 张`
    },
  },
  grid: { left: 45, right: 20, bottom: 50, top: 25 },
  xAxis: { type: 'category', data: data.value.bedUtilization.map((x: Row) => x.ward_name), axisLabel: { rotate: 25 } },
  yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
  series: [{ type: 'bar', data: data.value.bedUtilization.map((x: Row) => bedOccupancyRate(x)), itemStyle: { color: '#0f766e', borderRadius: [7, 7, 0, 0] } }],
}))
const revenueOption = computed(() => {
  const grouped: Record<string, number> = {}
  data.value.revenueComposition.forEach((x: Row) => { if (x.item_type) grouped[x.item_type] = (grouped[x.item_type] || 0) + Number(x.revenue) })
  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => `${params.marker}${params.name}<br/>收入：¥${formatChartNumber(params.value)}<br/>占比：${formatChartNumber(params.percent)}%`,
    },
    legend: { bottom: 0, left: 'center', padding: [8, 12], itemGap: 16 },
    // 保留原收入汇总与图例筛选口径；缩小环形图，为类别全名和占比预留两侧空间。
    series: [{
      type: 'pie', radius: ['32%', '52%'], center: ['50%', '44%'],
      label: { formatter: '{b}\n{d}%', alignTo: 'edge', edgeDistance: 12, distanceToLabelLine: 4, overflow: 'break', lineHeight: 18 },
      labelLine: { length: 12, length2: 8 },
      data: Object.entries(grouped).map(([name, value]) => ({ name: itemTypeLabels[name] || name, value })),
      itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 3 },
    }],
  }
})
const drugOption = computed(() => {
  const rows = [...data.value.drugUsage].reverse()
  return {
    tooltip: { show: false },
    grid: { left: 90, right: 20, bottom: 30, top: 20 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: rows.map((x: Row) => x.item_name) },
    series: [{
      type: 'bar',
      data: rows.map((x: Row) => x.used_quantity),
      label: {
        show: true,
        position: 'insideRight',
        distance: 10,
        color: '#fff',
        fontWeight: 600,
        formatter: (params: any) => `${params.value}${rows[params.dataIndex]?.unit ? ` ${rows[params.dataIndex].unit}` : ''}`,
      },
      itemStyle: { color: '#e0a044', borderRadius: [0, 7, 7, 0] },
    }],
  }
})
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header"><div><h2>经营与住院统计</h2><p>日期筛选作用于收入、药品和住院样本；床位与欠费指标反映当前状态。</p></div><div><el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /><el-button style="margin-left:8px" @click="load">刷新</el-button></div></div>
    <div class="metric-grid">
      <div class="metric-card"><span class="label">平均住院天数</span><div class="value">{{ data.lengthOfStayAndDebt.average_length_of_stay || 0 }}</div><div class="hint">根据有效样本计算</div></div>
      <div class="metric-card"><span class="label">欠费患者</span><div class="value">{{ data.lengthOfStayAndDebt.overdue_patient_count || 0 }}</div></div>
      <div class="metric-card"><span class="label">欠费总额</span><div class="value">¥{{ data.lengthOfStayAndDebt.overdue_amount || 0 }}</div></div>
      <div class="metric-card"><span class="label">有效样本数</span><div class="value">{{ data.lengthOfStayAndDebt.discharged_sample_size || 0 }}</div><div class="hint">所选日期内（默认六个月）入院且出院的人数</div></div>
    </div>
    <div class="panel" style="margin-bottom:20px"><h3>科室住院人数</h3><el-table :data="data.departmentCensus"><el-table-column prop="department_name" label="科室"/><el-table-column prop="current_inpatients" label="当前在院"/><el-table-column prop="admitted_count" label="期间入院"/><el-table-column prop="discharged_count" label="期间出院"/></el-table></div>
    <div class="two-columns statistics-charts">
      <div class="panel"><h3>病区床位占用率</h3><EChart :option="bedOption" /></div>
      <div class="panel"><h3>收入类别构成</h3><p class="muted" style="margin:4px 0 0;font-size:12px">点击下方类别名称，可显示或隐藏对应收入。</p><EChart :option="revenueOption" /></div>
    </div>
    <div class="panel" style="margin-top:20px"><h3>药品净使用量排行（发药减退药）</h3><EChart :option="drugOption" /></div>
  </div>
</template>

<style scoped>
.statistics-charts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.statistics-charts > .panel { min-width: 0; }
@media (max-width: 1200px) {
  .statistics-charts { grid-template-columns: minmax(0, 1fr); }
}
</style>
