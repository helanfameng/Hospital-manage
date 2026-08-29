import { afterEach, expect, it } from 'vitest'
import { createApp, h, nextTick, reactive, watch, type App } from 'vue'
import { ElInputNumber } from 'element-plus'
import { newOrderLine, plannedDose, suggestedDispenseQuantity } from '@/utils/orders'

let app: App | undefined
let root: HTMLDivElement | undefined
afterEach(() => { app?.unmount(); root?.remove() })

it('真实数字控件输入、提交和失焦后显示值与计划一致', async () => {
  const line = reactive(newOrderLine())
  const item = { dose_units_per_stock: 500 }
  watch(() => [line.dosage,line.plannedAdministrationCount], () => {
    const suggested = suggestedDispenseQuantity(line,item)
    if (suggested !== undefined) line.quantity = suggested
  })
  root = document.createElement('div'); document.body.append(root)
  app = createApp({ render: () => h('div', [
    h(ElInputNumber, { modelValue: line.dosage, min: 0.001, precision: 3, 'onUpdate:modelValue': value => { line.dosage = value ?? undefined } }),
    h(ElInputNumber, { modelValue: line.plannedAdministrationCount, min: 1, max: 1000, precision: 0, 'onUpdate:modelValue': value => { line.plannedAdministrationCount = value! } }),
    h(ElInputNumber, { modelValue: line.quantity, min: 1, precision: 0 }),
  ]) }); app.mount(root); await nextTick()
  const inputs = root.querySelectorAll('input')
  for (const [index,value] of [[0,'500'],[1,'2']] as const) {
    const input = inputs[index]!
    input.focus(); input.value = value
    input.dispatchEvent(new Event('input',{ bubbles:true })); await nextTick(); await nextTick()
    input.dispatchEvent(new Event('change',{ bubbles:true })); await nextTick()
    input.blur(); await nextTick()
  }
  expect(inputs[0]!.value).toBe('500.000')
  expect(inputs[1]!.value).toBe('2')
  expect(inputs[2]!.value).toBe('2')
  expect(plannedDose(line)).toBe(1000)
})
