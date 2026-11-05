import { afterEach, describe, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, inject, nextTick, provide, type App, type ComputedRef } from 'vue'
import { getData, postData } from '@/api/client'
import FinanceView from './FinanceView.vue'
import type { Row } from '@/types/api'

vi.mock('@/api/client', () => ({ getData: vi.fn(), postData: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() } }))
vi.mock('@/components/AdmissionPicker.vue', () => ({ default: {
  emits: ['update:modelValue'], template: '<button @click="$emit(\'update:modelValue\', 101)">选择测试患者</button>',
} }))

let app: App
let root: HTMLDivElement
const wrap = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) })
async function mount(rows: Row[], charges: Row[] = []) {
  vi.clearAllMocks()
  vi.mocked(getData).mockImplementation(async url => url === '/deposits' ? rows : url === '/charges' ? charges : { status: 'DISCHARGED' })
  root = document.createElement('div')
  app = createApp(FinanceView)
  for (const name of ['ElDescriptions','ElDescriptionsItem','ElTabs','ElTabPane','ElButton']) app.component(name, wrap)
  app.component('ElTable', defineComponent({
    props: ['data'], setup: (props, { slots }) => {
      provide('tableRows', computed(() => props.data))
      return () => h('section', slots.default?.())
    },
  }))
  app.component('ElTableColumn', defineComponent({
    props: ['label', 'prop', 'showOverflowTooltip'], setup: (props, { slots }) => {
      const data = inject<ComputedRef<Row[]>>('tableRows')!
      return () => h('div', { 'data-column': props.label, 'data-tooltip': props.showOverflowTooltip !== undefined }, data.value.map(row =>
        h('span', { class: 'cell' }, slots.default?.({ row }) ?? row[props.prop])))
    },
  }))
  app.mount(root)
  root.querySelector('button')!.click()
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
}
afterEach(() => app?.unmount())

describe('押金流水冲正原因', () => {
  it('费用数量带单位，保留冲正负数，不猜测缺失单位', async () => {
    await mount([], [{ quantity: 2, unit: '袋' }, { quantity: 1, unit: '盒' }, { quantity: -1, unit: '次' }, { quantity: 1 }])
    expect(Array.from(root.querySelectorAll('[data-column="数量"] .cell'), cell => cell.textContent)).toEqual(['2 袋','1 盒','-1 次','1 单位未记录'])
    expect(postData).not.toHaveBeenCalled()
  })
  it('费用显示来源医嘱，床位费标为系统自动费用，其余缺少关联不猜测医嘱且保持账目只读', async () => {
    await mount([], [{ order_no: '2026082800000305' }, { order_no: null, item_type: 'BED_FEE' }, { order_no: null }])
    const column=root.querySelector('[data-column="来源医嘱号"]')!
    expect(Array.from(column.querySelectorAll('.cell'), cell => cell.textContent)).toEqual(['2026082800000305','系统自动费用','无关联医嘱'])
    expect(column.previousElementSibling?.getAttribute('data-column')).toBe('费用号')
    expect(column.getAttribute('data-tooltip')).toBe('true')
    expect(postData).not.toHaveBeenCalled()
  })
  it('在时间和操作之间展示原有原因，普通流水不把备注当作冲正原因', async () => {
    await mount([
      { transaction_type: 'REVERSAL', note: '重复登记押金' },
      { transaction_type: 'REVERSAL', note: '500' },
      { transaction_type: 'SUPPLEMENT', note: '住院期间补交押金', is_reversed: true },
      { transaction_type: 'REVERSAL', note: null },
    ])
    const column = root.querySelector('[data-column="冲正原因"]')!
    expect(column.previousElementSibling?.getAttribute('data-column')).toBe('时间')
    expect(column.nextElementSibling?.getAttribute('data-column')).toBe('操作')
    expect(Array.from(column.querySelectorAll('.cell'), cell => cell.textContent)).toEqual(['重复登记押金','500','—','未记录'])
    expect(column.getAttribute('data-tooltip')).toBe('true')
    expect(postData).not.toHaveBeenCalled()
  })
  it('长原因完整保留，内容作为普通文字而不是HTML渲染', async () => {
    const reason = '核对收款后发现重复登记。'.repeat(20) + '<b>原因</b>'
    await mount([{ transaction_type: 'REVERSAL', note: reason }])
    const column = root.querySelector('[data-column="冲正原因"]')!
    expect(column.textContent).toBe(reason)
    expect(column.querySelector('b')).toBeNull()
  })
})
