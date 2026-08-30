import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { getData } from '@/api/client'
import StatisticsView from './StatisticsView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn() }))
const captured = vi.hoisted(() => ({ options: [] as any[] }))
vi.mock('@/components/EChart.vue', () => ({ default: defineComponent({
  props: ['option'], setup: (props) => () => { captured.options.push(props.option); return h('div', { class: 'test-chart' }) },
}) }))
let app: App
async function mount(revenue: Record<string, unknown>[]) {
  captured.options = []
  vi.mocked(getData).mockResolvedValue({ departmentCensus: [], bedUtilization: [], revenueComposition: revenue, drugUsage: [], lengthOfStayAndDebt: {} })
  app = createApp(StatisticsView)
  app.directive('loading', {})
  for (const name of ['ElDatePicker', 'ElButton', 'ElTable', 'ElTableColumn']) app.component(name, { render: () => null })
  app.mount(document.createElement('div'))
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
  return captured.options.filter(option => option.series[0].type === 'pie').at(-1)
}
afterEach(() => app?.unmount())

describe('收入构成图布局', () => {
  it('保留收入汇总和图例交互，类别和占比标签换行而非截断', async () => {
    const option = await mount([
      { item_type: 'BED_FEE', revenue: '100' }, { item_type: 'BED_FEE', revenue: '50' },
      { item_type: 'EXAMINATION', revenue: '80' }, { item_type: 'DRUG', revenue: '20' }, { item_type: 'TREATMENT', revenue: '10' },
    ])
    expect(option.series[0].data).toEqual([{ name: '床位费', value: 150 }, { name: '检查费', value: 80 }, { name: '药品费', value: 20 }, { name: '治疗费', value: 10 }])
    expect(option.series[0].label).toMatchObject({ formatter: '{b}\n{d}%', alignTo: 'edge', overflow: 'break' })
    expect(option.series[0].radius).toEqual(['32%', '52%'])
    expect(option.legend.bottom).toBe(0)
    expect(option.legend.selectedMode).not.toBe(false)
    expect(option.tooltip.formatter({ marker: '', name: '床位费', value: 150, percent: 57.69 })).toContain('收入：¥150<br/>占比：57.69%')
  })
  it('新增医嘱类别也用中文，空数据不制造收入', async () => {
    const option = await mount(['LAB', 'NURSING', 'DIET', 'OTHER'].map(item_type => ({ item_type, revenue: 0 })))
    expect(option.series[0].data.map((row: any) => row.name)).toEqual(['检验费', '护理费', '饮食费', '其他费用'])
  })
  it('空收入保持空图', async () => {
    const option = await mount([])
    expect(option.series[0].data).toEqual([])
  })
})
