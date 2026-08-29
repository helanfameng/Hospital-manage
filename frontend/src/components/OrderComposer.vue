<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from './AdmissionPicker.vue'
import { hasAdministrationPlan, isSingleExecutionItem, medicationPlanWarning, newOrderLine, orderCategories, orderLineError, orderLinePayload, plannedDose, selectOrderItem, suggestedDispenseQuantity, type OrderDraftLine } from '@/utils/orders'
import type { Row } from '@/types/api'
const emit = defineEmits<{ cancel: []; created: [] }>()
const loading = ref(true), saving = ref(false), catalog = ref<Row[]>([]), templates = ref<Row[]>([])
const templateId = ref<number>()
const form = reactive({ admissionId: undefined as number | undefined, note: '', items: [newOrderLine()] })
const itemFor = (line: OrderDraftLine) => catalog.value.find(i => Number(i.id) === line.medicalItemId)
const choices = (line: OrderDraftLine) => catalog.value.filter(i => i.item_type === line.category)
const selected = (line: OrderDraftLine) => selectOrderItem(line, itemFor(line))
watch(() => form.items.map(line => ({ key: line.key, item: line.medicalItemId, dosage: line.dosage, count: line.plannedAdministrationCount, type: line.orderType, category: line.category })), (current, previous) => {
  for (const entry of current) {
    if (JSON.stringify(entry) === JSON.stringify(previous?.find(old => old.key === entry.key))) continue
    const line = form.items.find(item => item.key === entry.key)!
    const suggested = suggestedDispenseQuantity(line,itemFor(line))
    if (suggested !== undefined) line.quantity = suggested
  }
})
const canSave = computed(() => !loading.value && !saving.value && form.admissionId && form.items.length && form.items.every(l => !orderLineError(l, itemFor(l))))
function categoryChanged(line: OrderDraftLine) { selectOrderItem(line); }
function applyTemplate() {
  const template = templates.value.find(t => t.id === templateId.value)
  if (!template) return
  const lines: OrderDraftLine[] = []
  for (const entry of template.items) {
    const item = catalog.value.find(i => Number(i.id) === Number(entry.medical_item_id))
    if (!item) { ElMessage.warning('模板包含不可用项目，请先维护模板'); return }
    const line = newOrderLine(item.item_type); selectOrderItem(line, item); lines.push(line)
  }
  if (form.items.length === 1 && !form.items[0]?.medicalItemId) form.items.splice(0, 1)
  form.items.push(...lines); templateId.value = undefined
}
async function saveTemplate() {
  const ids = [...new Set(form.items.map(l => l.medicalItemId).filter(Boolean))]
  if (!ids.length) return
  try {
    const { value } = await ElMessageBox.prompt('只保存项目清单，不保存患者、剂量或给药指令。', '保存项目模板', { inputPattern: /\S/, inputErrorMessage: '请输入模板名称' })
    await postData('/order-templates', { name: value.trim(), medicalItemIds: ids })
    templates.value = await getData('/order-templates'); ElMessage.success('项目模板已保存')
  } catch { /* Cancel or API error already displayed. */ }
}
async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    await postData('/orders', { admissionId: form.admissionId, note: form.note, items: form.items.map(line => orderLinePayload(line, itemFor(line))) })
    ElMessage.success('医嘱草稿已创建'); emit('created')
  } catch { /* Keep the draft open for correction. */ }
  finally { saving.value = false }
}
onMounted(async () => { try { [catalog.value, templates.value] = await Promise.all([getData<Row[]>('/order-catalog'), getData<Row[]>('/order-templates')]) } finally { loading.value = false } })
</script>

<template>
  <el-dialog :model-value="true" title="开立新医嘱" width="980px" style="max-width:calc(100vw - 32px)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="!saving && emit('cancel')">
    <div v-loading="loading" class="composer-body">
      <el-form label-position="top" :disabled="saving || loading">
        <el-form-item label="住院患者" required><AdmissionPicker v-model="form.admissionId" status="IN_HOSPITAL" :disabled="saving" /></el-form-item>
        <div class="toolbar"><el-select v-model="templateId" placeholder="可选：项目清单模板" clearable style="width:280px"><el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.name" /></el-select><el-button :disabled="!templateId" @click="applyTemplate">添加模板项目</el-button><el-button text @click="saveTemplate">保存当前项目清单为模板</el-button></div>
        <div v-for="(line,index) in form.items" :key="line.key" class="order-line">
          <div class="line-heading"><strong>医嘱项目 {{ index + 1 }}</strong><el-button text type="danger" @click="form.items.splice(index,1)">移除</el-button></div>
          <div class="line-grid">
            <el-form-item label="类别"><el-select v-model="line.category" aria-label="医嘱类别" @change="categoryChanged(line)"><el-option v-for="(label,value) in orderCategories" :key="value" :label="label" :value="value" /></el-select></el-form-item>
            <el-form-item label="医疗项目" class="wide" required><el-select v-model="line.medicalItemId" filterable aria-label="医疗项目" placeholder="请选择项目" @change="selected(line)"><el-option v-for="i in choices(line)" :key="i.id" :value="Number(i.id)" :label="`${i.name}${i.specification ? ' · '+i.specification : ''} · ${i.billable ? '¥'+i.unit_price+'/'+i.unit : '不收费'}`" /></el-select></el-form-item>
          </div>
          <template v-if="line.medicalItemId">
            <p class="muted item-summary">{{ itemFor(line)?.specification || '按项目资料执行' }}<template v-if="line.category==='DRUG'"> · {{ itemFor(line)?.dosage_form || '剂型待维护' }} · 1 {{ itemFor(line)?.unit }} = {{ itemFor(line)?.dose_units_per_stock || '待维护' }} {{ itemFor(line)?.dose_unit }}</template></p>
            <div class="line-grid">
              <el-form-item v-if="line.category==='DRUG'" :label="`单次用量（${line.doseUnit || '单位待维护'}）`" required><el-input-number v-model="line.dosage" aria-label="单次用量" :min="0.001" :precision="3" /></el-form-item>
              <el-form-item v-if="hasAdministrationPlan(line)" label="计划给药总次数" required><el-input-number v-model="line.plannedAdministrationCount" aria-label="计划给药总次数" :min="1" :max="1000" :precision="0" :step="1" step-strictly /></el-form-item>
              <el-form-item v-if="line.category==='DRUG' || (itemFor(line)?.billable && !isSingleExecutionItem(itemFor(line)))" :label="`${line.category==='DRUG' ? '计划发药数量' : '计费数量'}（${itemFor(line)?.unit}）`" required><el-input-number v-model="line.quantity" aria-label="项目数量" :min="itemFor(line)?.allow_fractional_quantity ? 0.001 : 1" :precision="itemFor(line)?.allow_fractional_quantity ? 3 : 0" /></el-form-item>
              <el-form-item v-if="line.category==='DRUG'" label="给药途径" required><el-select v-model="line.route" aria-label="给药途径" placeholder="请选择给药途径"><el-option v-for="route in String(itemFor(line)?.allowed_routes || '').split(',').filter(Boolean)" :key="route" :label="route" :value="route" /></el-select></el-form-item>
              <el-form-item label="医嘱类型"><el-select v-model="line.orderType" aria-label="项目医嘱类型"><el-option label="临时医嘱" value="TEMPORARY" /><el-option label="长期医嘱" value="LONG_TERM" /></el-select></el-form-item>
              <el-form-item label="开始时间（留空为提交前录入时刻）"><el-date-picker v-model="line.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" /></el-form-item>
              <el-form-item label="计划结束时间（可选）"><el-date-picker v-model="line.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间" /></el-form-item>
            </div>
            <p v-if="hasAdministrationPlan(line) && suggestedDispenseQuantity(line,itemFor(line))!==undefined" class="muted item-summary">计划总用量：{{ plannedDose(line) }} {{ line.doseUnit }}；建议发药：{{ suggestedDispenseQuantity(line,itemFor(line)) }} {{ itemFor(line)?.unit }}。按总次数逐次确认，不设置每日次数或时间间隔。</p>
            <el-alert v-if="medicationPlanWarning(line,itemFor(line))" :title="medicationPlanWarning(line,itemFor(line))" type="warning" :closable="false" class="plan-warning" />
            <el-form-item v-if="hasAdministrationPlan(line) && suggestedDispenseQuantity(line,itemFor(line))!==undefined && line.quantity > suggestedDispenseQuantity(line,itemFor(line))!" label="超量发药原因" required><el-input v-model="line.excessDispenseReason" aria-label="超量发药原因" maxlength="255" placeholder="说明超出建议数量的原因，不能用它代替给药计划" /></el-form-item>
            <el-form-item v-if="line.category==='DRUG' && ['静脉滴注','静脉注射'].includes(line.route)" label="配液组（可选，相同组名的药品共同执行）"><el-input v-model="line.groupKey" maxlength="100" placeholder="同组途径、类型、起止时间和计划给药次数应一致" /></el-form-item>
            <el-form-item :label="line.category==='DRUG' ? '用药目的／执行说明' : '项目要求／执行说明'"><el-input v-model="line.instructions" type="textarea" maxlength="255" placeholder="填写本次医嘱的具体要求；不填写默认治疗方案" /></el-form-item>
            <p v-if="orderLineError(line,itemFor(line))" class="line-error">{{ orderLineError(line,itemFor(line)) }}</p>
          </template>
        </div>
        <el-button text type="primary" @click="form.items.push(newOrderLine())">+ 添加医嘱项目</el-button>
        <el-alert v-if="form.items.some(l=>l.groupKey)" title="配液分组仅记录医师指令，不代表已完成配伍安全审核。" type="warning" :closable="false" />
        <el-form-item label="整单备注" style="margin-top:16px"><el-input v-model="form.note" type="textarea" maxlength="500" /></el-form-item>
        <p class="muted">临时用药完成计划给药总次数后结束；其他临时项目执行一次后结束。长期项目由医师完成或停止。余药不增加给药次数。</p>
      </el-form>
    </div>
    <template #footer><el-button :disabled="saving" @click="emit('cancel')">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存草稿</el-button></template>
  </el-dialog>
</template>
<style scoped>
.composer-body { max-height: 65vh; overflow-y: auto; padding-right: 8px; }
.order-line { background: #f5f9f8; padding: 16px; border: 1px solid #dbe7e5; border-radius: 12px; margin-bottom: 14px; }
.line-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.line-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 0 16px; }
.wide { grid-column: span 2; }
.line-grid .el-form-item { min-width: 0; }
.line-grid :deep(.el-select), .line-grid :deep(.el-input-number), .line-grid :deep(.el-date-editor) { width: 100%; min-width: 0; }
.item-summary { margin: -4px 0 16px; font-size: 12px; }
.line-error { color: #b45309; margin: 0; }
.plan-warning { margin-bottom: 16px; }
@media(max-width:700px) { .line-grid { grid-template-columns: 1fr; } .wide { grid-column: auto; } }
</style>
