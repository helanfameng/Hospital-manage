import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { getData } from '@/api/client'
import StatisticsView from './StatisticsView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
const captured = vi.hoisted(() => ({ options: [] as any[] }))
vi.mock('@/components/EChart.vue', () => ({ default: defineComponent({
  props: ['option'], setup: (props) => () => { captured.options.push(props.option); return h('div', { class: 'test-chart' }) },
}) }))

let app: App
async function mount() {
  captured.options = []
  vi.mocked(getData).mockResolvedValue({
    currentOperations: { current_inpatients: 12, available_beds: 8, occupied_beds: 12 },
    admissionTrend: [
      { stat_date: '2026-09-01', inpatient_census: 10, admitted_count: 2, discharged_count: 1 },
      { stat_date: '2026-09-02', inpatient_census: 12, admitted_count: 3, discharged_count: 1 },
    ],
    departmentCensus: [], bedUtilization: [],
    lengthOfStayAndDebt: { average_length_of_stay: 6.5, median_length_of_stay: 5, p90_length_of_stay: 12, discharged_sample_size: 20 },
    lengthOfStayDistribution: [{ stay_bucket: '4-7天', patient_count: 9 }],
  })
  app = createApp(StatisticsView)
  app.directive('loading', {})
  for (const name of ['ElDatePicker', 'ElButton', 'ElTable', 'ElTableColumn']) app.component(name, { render: () => null })
  const root = document.createElement('div')
  app.mount(root)
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
  return root
}
afterEach(() => app?.unmount())

describe('住院运行统计', () => {
  it('展示当前运行指标并生成连续趋势序列', async () => {
    const root = await mount()
    expect(root.textContent).toContain('当前运行')
    expect(root.textContent).toContain('当前在院12')
    expect(root.textContent).toContain('汇总住院运行、床位使用与住院效率数据')
    expect(root.textContent).toContain('未选择日期时默认近 6 个月')
    const trend = captured.options.filter(option => option.series?.some((series: any) => series.name === '每日在院')).at(-1)
    expect(trend.xAxis.data).toEqual(['09-01', '09-02'])
    expect(trend.series.map((series: any) => series.data)).toEqual([[10, 12], [2, 3], [1, 1]])
  })

  it('住院日分布补齐没有病例的区间', async () => {
    await mount()
    const distribution = captured.options.filter(option => option.xAxis?.data?.includes('30天以上')).at(-1)
    expect(distribution.xAxis.data).toEqual(['0-3天', '4-7天', '8-14天', '15-30天', '30天以上'])
    expect(distribution.series[0].data).toEqual([0, 9, 0, 0, 0])
    expect(distribution.yAxis).toMatchObject({ name: '出\n院\n人\n数', nameLocation: 'middle', nameGap: 42, nameRotate: 0 })
    expect(distribution.grid.left).toBe(64)
  })
})
