import { afterEach, describe, expect, it, vi } from 'vitest'
import { computed, createApp, defineComponent, h, inject, nextTick, provide, type App, type ComputedRef } from 'vue'
import { getData } from '@/api/client'
import { auditActionLabels, auditLabel, auditObjectLabel } from '@/utils/labels'
import AuditView from './AuditView.vue'

vi.mock('@/api/client', () => ({ getData: vi.fn() }))
let app: App
let root: HTMLDivElement
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function mount() {
  vi.clearAllMocks()
  vi.mocked(getData).mockResolvedValue({ total: 45, items: [
    { action: 'USER_STATUS', object_type: 'user_account', object_id: '6' },
    { action: 'DISCHARGE_WITHDRAW', object_type: 'admission', object_id: '102' },
    { action: 'REQUEST_FAILED', object_type: 'request', object_id: null },
    { action: 'BED_STATUS', object_type: 'bed', object_id: '12' },
    { action: 'PASSWORD_CHANGE', object_type: 'user_account', object_id: '1' },
  ] })
  root = document.createElement('div')
  app = createApp(AuditView)
  app.component('ElInput', { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' })
  app.component('ElSelect', { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="">全部操作</option><slot /></select>' })
  app.component('ElOption', { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' })
  app.component('ElButton', { template: '<button><slot /></button>' })
  app.component('ElPagination', { props: ['currentPage'], emits: ['current-change'], template: '<button data-pagination @click="$emit(\'current-change\', currentPage + 1)">{{ currentPage }}</button>' })
  app.component('ElTable', defineComponent({ props: ['data'], setup: (props, { slots }) => {
    provide('rows', computed(() => props.data)); return () => h('section', slots.default?.())
  } }))
  app.component('ElTableColumn', defineComponent({ props: ['label', 'prop'], setup: (props, { slots }) => {
    const rows = inject<ComputedRef<Record<string, unknown>[]>>('rows')!
    return () => h('div', { 'data-column': props.label }, rows.value.map(row => h('div', slots.default?.({ row }) ?? String(row[props.prop] ?? ''))))
  } }))
  app.mount(root)
  await flush()
}
afterEach(() => app?.unmount())

describe('审计日志中文展示与筛选', () => {
  it('补齐操作与对象中文，空对象编号不显示井号或空编号', async () => {
    await mount()
    const actions = root.querySelector('[data-column="操作"]')!.textContent
    for (const label of ['调整用户状态', '撤回出院申请', '请求失败', '调整床位状态', '修改密码']) expect(actions).toContain(label)
    expect(actions).not.toMatch(/[A-Z_]/)
    const objects = root.querySelector('[data-column="对象"]')!.textContent
    expect(objects).toContain('系统用户（编号：6）')
    expect(objects).toContain('系统请求')
    expect(objects).not.toMatch(/request|#|null|系统请求（/)
  })

  it('中文操作选项发送原编码，重新查询回到第一页，清空后查询全部操作', async () => {
    await mount()
    const select = root.querySelector('select')!
    expect(select.getAttribute('placeholder')).toBe('操作')
    expect(root.textContent).not.toContain('操作编码')
    root.querySelector<HTMLButtonElement>('[data-pagination]')!.click()
    await flush()
    expect(root.querySelector('[data-pagination]')!.textContent).toBe('2')
    select.value = 'USER_STATUS'
    select.dispatchEvent(new Event('change'))
    const input = root.querySelector('input')!
    input.value = 'admin'; input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector('button')!.click(); await flush()
    expect(getData).toHaveBeenLastCalledWith('/admin/audit-logs', { username: 'admin', action: 'USER_STATUS', page: 1, size: 20 })
    expect(root.querySelector('[data-pagination]')!.textContent).toBe('1')
    select.value = ''; select.dispatchEvent(new Event('change')); await nextTick()
    root.querySelector('button')!.click(); await flush()
    expect(getData).toHaveBeenLastCalledWith('/admin/audit-logs', { username: 'admin', action: '', page: 1, size: 20 })
  })

  it('历史中文原样保留，未识别编码有中文兜底，编号保留包括零', () => {
    expect(auditLabel(auditActionLabels, '历史操作', '其他操作')).toBe('历史操作')
    expect(auditLabel(auditActionLabels, 'FUTURE_ACTION', '其他操作')).toBe('其他操作')
    expect(auditObjectLabel('future_object', 0)).toBe('其他对象（编号：0）')
    expect(auditObjectLabel(null, null)).toBe('—')
  })
})
