import { afterEach, expect, it } from 'vitest'
import { createApp, h, nextTick, reactive, watch, type App } from 'vue'
import { ElInputNumber } from 'element-plus'
import { newOrderLine, suggestedDispenseQuantity } from '@/utils/orders'

let app: App | undefined
let root: HTMLDivElement | undefined
afterEach(() => { app?.unmount(); root?.remove() })

it('输入单次剂量后自动计算单次建议发药量', async () => {
  const line = reactive(newOrderLine())
  const item = { item_type:'DRUG',dose_units_per_stock:500,allow_fractional_quantity:false }
  watch(() => line.dosePerAdministration, () => { line.requestedDispenseQuantity=suggestedDispenseQuantity(line,item) })
  root = document.createElement('div'); document.body.append(root)
  app = createApp({ render: () => h('div', [
    h(ElInputNumber, { modelValue: line.dosePerAdministration, min:0.001, precision:3, 'onUpdate:modelValue': value => { line.dosePerAdministration=value ?? undefined } }),
    h(ElInputNumber, { modelValue: line.requestedDispenseQuantity, min:1, precision:0 }),
  ]) }); app.mount(root); await nextTick()
  const inputs=root.querySelectorAll('input'), input=inputs[0]!
  input.value='500'; input.dispatchEvent(new Event('input',{ bubbles:true })); await nextTick(); await nextTick()
  input.dispatchEvent(new Event('change',{ bubbles:true })); await nextTick()
  expect(inputs[0]!.value).toBe('500.000')
  expect(inputs[1]!.value).toBe('1')
})
