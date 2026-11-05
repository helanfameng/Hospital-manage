import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { getData, postData } from '@/api/client'
import OrdersView from './OrdersView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn(), postData: vi.fn() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ can: () => true }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() } }))
vi.mock('@/components/AdmissionPicker.vue', () => ({ default: {
  props: ['modelValue', 'disabled'], emits: ['update:modelValue'],
  template: '<input aria-label="住院患者" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
} }))

const wrap = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) })
let app: App
let root: HTMLDivElement
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const button = (text: string) => Array.from(root.querySelectorAll('button')).find(el => el.textContent === text)!

async function setValue(selector: string, value: string, event = 'input') {
  const field = root.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!
  field.value = value
  field.dispatchEvent(new Event(event, { bubbles: true }))
  await nextTick()
}

beforeEach(async () => {
  vi.clearAllMocks()
  vi.mocked(getData).mockImplementation(async url => url === '/order-catalog'
    ? [{ id: 1, name: '测试药品', item_type: 'DRUG', unit_price: 10, unit: '盒', billable: true, dose_unit: '粒', dose_units_per_stock: 24, allowed_routes: '口服' }, { id: 2, name: '二级护理', item_type: 'NURSING', unit_price: 0, unit: '次', billable: false },
       ...['EXAMINATION','LAB','TREATMENT'].map((item_type,index)=>({ id: index+3, name: '按次项目', item_type, unit_price: 20, unit: '次', billable: true })),
       { id: 6, name: '按部位项目', item_type: 'TREATMENT', unit_price: 10, unit: '部位', billable: true },
       { id: 7, name: '测试液体药品', item_type: 'DRUG', unit_price: 5, unit: '袋', billable: true, dose_unit: 'mL', dose_units_per_stock: 500, allowed_routes: '静脉滴注' }]
    : url === '/order-templates' ? [] : url === '/medication-frequencies' ? [
      { code: 'ONCE', name: '一次', interval_minutes: 0, once_only: true },
      { code: 'QD', name: '每日1次', interval_minutes: 1440, once_only: false },
    ] : { items: [], total: 0 })
  vi.mocked(postData).mockResolvedValue({ id: 1 })
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(OrdersView)
  for (const name of ['ElForm', 'ElRow', 'ElCol', 'ElAlert', 'ElDatePicker', 'ElTag']) app.component(name, wrap)
  app.component('ElFormItem', { props: ['label'], template: '<div><label>{{ label }}</label><slot /></div>' })
  app.directive('loading', {})
  for (const name of ['ElTable', 'ElTableColumn']) app.component(name, { render: () => null })
  app.component('ElPagination', { props: ['currentPage'], emits: ['update:currentPage','current-change'], template: '<button @click="$emit(\'update:currentPage\', currentPage+1); $emit(\'current-change\')">下一页</button>' })
  app.component('ElDialog', defineComponent({ props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? h('section', [slots.default?.(), slots.footer?.()]) : null }))
  app.component('ElButton', { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' })
  app.component('ElInput', { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' })
  app.component('ElInputNumber', { props: ['modelValue'], emits: ['update:modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' })
  app.component('ElSelect', { props: ['modelValue'], emits: ['update:modelValue','change'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', Number($event.target.value) || $event.target.value); $emit(\'change\')"><option value="">请选择</option><slot /></select>' })
  app.component('ElOption', { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' })
  app.mount(root)
  await flush()
  button('开立医嘱').click()
  await flush()
})
afterEach(() => { app.unmount(); root.remove() })

describe('医嘱列表筛选', () => {
  it('姓名、医嘱号和状态组合查询，去除首尾空格', async () => {
    button('取消').click(); await flush()
    await setValue('[aria-label="筛选患者姓名"]', '  朱立  ')
    await setValue('[aria-label="筛选医嘱号"]', '  2026082800000305  ')
    await setValue('[aria-label="医嘱状态"]', 'SUBMITTED', 'change'); await flush()
    expect(getData).toHaveBeenLastCalledWith('/orders', { patientName: '朱立', orderNo: '2026082800000305', status: 'SUBMITTED', page: 1, size: 20 })
  })
  it('查询支持回车，翻页保留已查询条件，重新查询回第一页', async () => {
    button('取消').click(); await flush()
    await setValue('[aria-label="筛选患者姓名"]', '张')
    root.querySelector('input[aria-label="筛选患者姓名"]')!.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true })); await flush()
    expect(getData).toHaveBeenLastCalledWith('/orders', expect.objectContaining({ patientName: '张', page: 1 }))
    await setValue('[aria-label="筛选患者姓名"]', '李')
    button('下一页').click(); await flush()
    expect(getData).toHaveBeenLastCalledWith('/orders', expect.objectContaining({ patientName: '张', page: 2 }))
    button('查询').click(); await flush()
    expect(getData).toHaveBeenLastCalledWith('/orders', expect.objectContaining({ patientName: '李', page: 1 }))
  })
  it('重置同时清空姓名、编号、状态并回到第一页', async () => {
    button('取消').click(); await flush()
    await setValue('[aria-label="筛选患者姓名"]', '朱')
    await setValue('[aria-label="筛选医嘱号"]', '0305')
    await setValue('[aria-label="医嘱状态"]', 'SUBMITTED', 'change'); await flush()
    button('下一页').click(); await flush()
    button('重置筛选').click(); await flush()
    expect(getData).toHaveBeenLastCalledWith('/orders', { patientName: '', orderNo: '', status: '', page: 1, size: 20 })
    expect(root.querySelector<HTMLInputElement>('[aria-label="筛选患者姓名"]')!.value).toBe('')
    expect(root.querySelector<HTMLInputElement>('[aria-label="筛选医嘱号"]')!.value).toBe('')
  })
})

describe('分类医嘱表单', () => {
  it('输入单次用量和给药频次，发药批量独立保存', async () => {
    await setValue('[aria-label="住院患者"]', '101')
    await setValue('[aria-label="医疗项目"]', '7', 'change')
    await setValue('[aria-label="单次用量"]', '500')
    expect(root.querySelector<HTMLInputElement>('[aria-label="项目数量"]')!.value).toBe('1')
    await setValue('[aria-label="给药频次"]', 'ONCE', 'change')
    await setValue('[aria-label="给药途径"]', '静脉滴注', 'change')
    await setValue('[aria-label="项目数量"]', '2')
    expect(root.textContent).toContain('一次')
    expect(root.textContent).toContain('每次给药只记录实际执行事实')
    expect(button('保存草稿').disabled).toBe(false)
    button('保存草稿').click(); await flush()
    expect(postData).toHaveBeenCalledWith('/orders', expect.objectContaining({ items: [expect.objectContaining({ quantity: 2, dosage: 500, frequencyCode: 'ONCE', firstDoseAt: null })] }))
  })
  it('添加其他项目不覆盖批量，切长期保留频次且不要求结束时间', async () => {
    await setValue('[aria-label="住院患者"]', '101')
    await setValue('[aria-label="医疗项目"]', '7', 'change')
    await setValue('[aria-label="单次用量"]', '500')
    await setValue('[aria-label="给药途径"]', '静脉滴注', 'change')
    await setValue('[aria-label="项目数量"]', '2')
    await setValue('[aria-label="项目医嘱类型"]', 'LONG_TERM', 'change')
    expect(button('保存草稿').disabled).toBe(false)
    button('+ 添加医嘱项目').click(); await flush()
    expect(root.querySelector<HTMLInputElement>('[aria-label="项目数量"]')!.value).toBe('2')
    Array.from(root.querySelectorAll('button')).filter(b=>b.textContent==='移除')[1]!.click(); await flush()
    expect(root.querySelector('[aria-label="给药频次"]')).not.toBeNull()
    button('保存草稿').click(); await flush()
    expect(postData).toHaveBeenCalledWith('/orders', expect.objectContaining({ items: [expect.objectContaining({ quantity: 2, frequencyCode: 'QD', firstDoseAt: null })] }))
  })
  it.each([['EXAMINATION',3],['LAB',4],['TREATMENT',5]])('%s按次项目不显示数量输入，保存固定数量1', async (category,id) => {
    await setValue('[aria-label="住院患者"]', '101')
    await setValue('[aria-label="医嘱类别"]', String(category), 'change')
    await setValue('[aria-label="医疗项目"]', String(id), 'change')
    expect(root.querySelector('[aria-label="项目数量"]')).toBeNull()
    expect(root.querySelector('[aria-label="单次用量"]')).toBeNull()
    expect(button('保存草稿').disabled).toBe(false)
    button('保存草稿').click(); await flush()
    expect(postData).toHaveBeenCalledWith('/orders', expect.objectContaining({ items: [expect.objectContaining({ medicalItemId: id, quantity: 1, dosage: null })] }))
  })
  it('按其他明确单位计价的项目保留计费数量', async () => {
    await setValue('[aria-label="医嘱类别"]', 'TREATMENT', 'change')
    await setValue('[aria-label="医疗项目"]', '6', 'change')
    expect(root.textContent).toContain('计费数量（部位）')
    expect(root.querySelector('[aria-label="项目数量"]')).not.toBeNull()
  })
  it('显示频次，药品单次用量没有默认数值', async () => {
    await setValue('[aria-label="医疗项目"]', '1', 'change')
    expect(root.textContent).toContain('给药频次')
    expect(root.querySelector<HTMLInputElement>('[aria-label="单次用量"]')!.value).toBe('')
    expect(root.textContent).toContain('单次用量（粒）')
    expect(root.textContent).toContain('本次发药批量（盒）')
    expect(button('保存草稿').disabled).toBe(true)
  })
  it('完整药品医嘱携带频次，剂量和计价数量分开', async () => {
    await setValue('[aria-label="住院患者"]', '101')
    await setValue('[aria-label="医疗项目"]', '1', 'change')
    await setValue('[aria-label="单次用量"]', '2')
    await setValue('[aria-label="给药途径"]', '口服', 'change')
    await setValue('[aria-label="项目医嘱类型"]', 'LONG_TERM', 'change')
    expect(button('保存草稿').disabled).toBe(false)
    button('保存草稿').click(); await flush()
    expect(postData).toHaveBeenCalledWith('/orders', expect.objectContaining({ admissionId: 101, items: [expect.objectContaining({ medicalItemId: 1, dosage: 2, doseUnit: '粒', quantity: 1, route: '口服', frequencyCode: 'QD' })] }))
  })
  it('护理项目不要求药品剂量或途径，移除项目不会串行', async () => {
    await setValue('[aria-label="医嘱类别"]', 'NURSING', 'change')
    await setValue('[aria-label="医疗项目"]', '2', 'change')
    expect(root.querySelector('[aria-label="单次用量"]')).toBeNull()
    expect(root.querySelector('[aria-label="给药途径"]')).toBeNull()
    button('+ 添加医嘱项目').click(); await nextTick()
    expect(root.querySelectorAll('.order-line')).toHaveLength(2)
    button('移除').click(); await nextTick()
    expect(root.querySelectorAll('.order-line')).toHaveLength(1)
    expect(root.querySelector<HTMLSelectElement>('[aria-label="医嘱类别"]')!.value).toBe('DRUG')
  })
})
