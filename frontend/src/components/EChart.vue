<script setup lang="ts">
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import type { EChartsCoreOption } from 'echarts/core'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

echarts.use([BarChart, PieChart, CanvasRenderer, GridComponent, LegendComponent, TooltipComponent])

const props = defineProps<{ option: EChartsCoreOption }>()
const root = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!root.value) return
  chart = echarts.init(root.value)
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(root.value)
})
watch(() => props.option, (value) => chart?.setOption(value, true), { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template><div ref="root" style="width:100%;height:340px" /></template>
