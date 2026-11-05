import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { getData, postData } from '@/api/client'
import AdmissionCreateView from './AdmissionCreateView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn(), postData: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() } }))
vi.mock('@/components/AdmissionPicker.vue', () => ({
  default: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<input aria-label="患者" :disabled="disabled" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' },
}))

const wrap = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.footer?.()]) })
const select = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  setup: (props, { emit, slots }) => () => h('select', {
    value: props.modelValue ?? '',
    onChange: (event: Event) => {
      const value = (event.target as HTMLSelectElement).value
      emit('update:modelValue', value !== '' && Number.isFinite(Number(value)) ? Number(value) : value)
    },
  }, [h('option', { value: '' }, '请选择'), slots.default?.()]),
})

let app: App
let root: HTMLDivElement
const created = vi.fn()
const cancel = vi.fn()
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }

async function mount() {
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(AdmissionCreateView, { onCreated: created, onCancel: cancel })
  for (const name of ['ElDialog', 'ElForm', 'ElRow', 'ElCol', 'ElFormItem']) app.component(name, wrap)
  app.component('ElAlert', { props: ['title'], template: '<p>{{ title }}</p>' })
  app.component('ElSelect', select)
  app.component('ElOption', { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' })
  app.component('ElInputNumber', { props: ['modelValue'], emits: ['update:modelValue'], template: '<input aria-label="押金" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' })
  app.component('ElButton', { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' })
  app.directive('loading', {})
  app.mount(root)
  await flush()
}

function input(label: string, value: string) {
  const field = root.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
}

async function choose(index: number, value: string) {
  const field = root.querySelectorAll('select')[index]!
  field.value = value
  field.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}

async function fill() {
  input('患者', '101')
  await choose(0, '1')
  await choose(1, '10')
  await choose(2, '20')
  await choose(3, 'CARD')
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getData).mockImplementation(async (url) => {
    if (url === '/admin/departments') return [{ id: 1, name: '内科', active: true }, { id: 2, name: '外科', active: true }]
    if (url === '/beds') return [{ id: 20, department_id: 1, ward_name: '一病区', room_no: '101', bed_no: 'A', active: true }]
    if (url === '/admin/staff') return [{ id: 10, name: '医生', active: true }]
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.mocked(postData).mockResolvedValue({ id: 1 })
})
afterEach(() => { app?.unmount(); root?.remove() })

describe('入院登记弹窗', () => {
  it('必填项完整后才允许提交，切换科室清空医生和床位', async () => {
    await mount()
    expect(root.querySelectorAll('button')[1]!.disabled).toBe(true)
    await fill()
    expect(root.querySelectorAll('button')[1]!.disabled).toBe(false)
    await choose(0, '2')
    expect(root.querySelectorAll('select')[1]!.value).toBe('')
    expect(root.querySelectorAll('select')[2]!.value).toBe('')
    expect(root.querySelectorAll('button')[1]!.disabled).toBe(true)
  })

  it('提交期间阻止重复请求和取消，成功后通知列表刷新', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(postData).mockReturnValue(new Promise(done => { resolve = done }))
    await mount()
    await fill()
    const buttons = root.querySelectorAll('button')
    buttons[1]!.click()
    buttons[1]!.click()
    buttons[0]!.click()
    await nextTick()
    expect(postData).toHaveBeenCalledTimes(1)
    expect(postData).toHaveBeenCalledWith('/admissions', { patientId: 101, departmentId: 1, doctorId: 10, bedId: 20, initialDeposit: 3000, paymentMethod: 'CARD' })
    expect(cancel).not.toHaveBeenCalled()
    expect(buttons[0]!.disabled).toBe(true)
    resolve({ id: 1 })
    await flush()
    expect(created).toHaveBeenCalledTimes(1)
  })

  it('提交失败保留表单且允许重试', async () => {
    vi.mocked(postData).mockRejectedValueOnce(new Error('床位已占用'))
    await mount()
    await fill()
    root.querySelectorAll('button')[1]!.click()
    await flush()
    expect(created).not.toHaveBeenCalled()
    expect(root.querySelector<HTMLInputElement>('input[aria-label="患者"]')!.value).toBe('101')
    expect(root.querySelectorAll('button')[1]!.disabled).toBe(false)
  })

  it('取消不提交，重新打开恢复默认值并重新获取可用床位', async () => {
    await mount()
    input('押金', '5000')
    root.querySelectorAll('button')[0]!.click()
    expect(cancel).toHaveBeenCalledTimes(1)
    expect(postData).not.toHaveBeenCalled()
    app.unmount()
    root.remove()
    await mount()
    expect(root.querySelector<HTMLInputElement>('input[aria-label="押金"]')!.value).toBe('3000')
    expect(vi.mocked(getData).mock.calls.filter(([url]) => url === '/beds')).toHaveLength(2)
  })

  it('基础选项加载失败时禁止提交并提示重新打开', async () => {
    vi.mocked(getData).mockRejectedValueOnce(new Error('网络异常'))
    await mount()
    expect(root.textContent).toContain('登记选项加载失败，请关闭窗口后重试')
    expect(root.querySelectorAll('button')[1]!.disabled).toBe(true)
  })
})
