import { afterEach, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, inject, nextTick, provide, type App, type ComputedRef } from 'vue'
import { getData, postData } from '@/api/client'
import type { Row } from '@/types/api'
import ExecutionsView from './ExecutionsView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn(), postData: vi.fn() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ can: () => true, canAny: () => true }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() } }))
vi.mock('@/components/AdmissionPicker.vue', () => ({ default: {
  emits: ['update:modelValue'], template: '<button @click="$emit(\'update:modelValue\', 101)">选择测试患者</button>',
} }))
let app: App
let root: HTMLDivElement
afterEach(() => app?.unmount())
it('待执行仅保留三个环节，执行历史各行显示来源医嘱号', async () => {
  vi.clearAllMocks()
  vi.mocked(getData).mockImplementation(async url => {
    if (url === '/orders/executions/history') return [
      { order_no: '2026082900000310', phase: 'DISPENSE', quantity: 1, unit: '盒', status: 'SUCCESS' },
      { order_no: '2026082900000310', phase: 'ADMINISTER', quantity: 3, unit: '粒', status: 'REVERSED' },
      { order_no: null, phase: 'PERFORM', quantity: 0, unit: '次', status: 'NOT_DONE' },
    ]
    if (url === '/orders/tasks') return [{ order_item_id: 1, item_name: '阿莫西林胶囊', item_type: 'DRUG', order_type: 'LONG_TERM', administration_count: 12, task_quantity: 1, task_unit: '盒', unit: '盒' }]
    return []
  })
  root = document.createElement('div'); app = createApp(ExecutionsView)
  const wrap = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) })
  for (const name of ['ElTabs','ElTabPane','ElRadioGroup','ElRadioButton','ElSelect','ElOption','ElPagination','ElButton']) app.component(name,wrap)
  app.directive('loading',{})
  app.component('ElTable',defineComponent({ props:['data'],setup:(props,{slots})=>{
    provide('rows',computed(()=>props.data)); return ()=>h('section',slots.default?.())
  } }))
  app.component('ElTableColumn',defineComponent({ props:['label','prop'],setup:(props,{slots})=>{
    const rows=inject<ComputedRef<Row[]>>('rows')!
    return ()=>h('div',{'data-column':props.label},rows.value.map(row=>h('span',{class:'cell'},slots.default?.({row})??row[props.prop])))
  } }))
  app.mount(root); await nextTick(); await new Promise(resolve=>setTimeout(resolve,0)); await nextTick()
  expect(root.textContent).toContain('药房发药护士给药非药品执行')
  expect(root.textContent).toContain('阿莫西林胶囊 · 药品 · 长期医嘱')
  expect(root.textContent).toContain('已给药 12 次')
  expect(root.textContent).not.toContain('不限定总次数')
  expect(root.textContent).not.toContain('历史医嘱')
  expect(getData).toHaveBeenCalledWith('/orders/tasks',expect.objectContaining({phase:'DISPENSE'}))
  root.querySelector('button')!.click()
  await new Promise(resolve=>setTimeout(resolve,0)); await nextTick()
  expect(Array.from(root.querySelectorAll('[data-column="来源医嘱号"] .cell'),cell=>cell.textContent)).toEqual(['2026082900000310','2026082900000310','无关联医嘱'])
  expect(root.textContent).not.toContain('历史医嘱执行')
  expect(vi.mocked(getData).mock.calls.some(([url])=>url==='/executions/pending')).toBe(false)
  expect(postData).not.toHaveBeenCalled()
})
