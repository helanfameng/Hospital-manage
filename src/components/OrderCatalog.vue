<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, postData } from '@/api/client'
import { orderCategories } from '@/utils/orders'
import type { Row } from '@/types/api'
const emit = defineEmits<{ close: [] }>()
const items = ref<Row[]>([]), routeOptions = ref<Row[]>([]), editing = ref(false), busy = ref(false), routes = ref<string[]>([])
const keyword = ref(''), categoryFilter = ref('')
const blank = () => ({ id: undefined as number | undefined, code: '', name: '', category: 'DRUG', unit: '', price: 0, billable: true, allowFractionalQuantity: false, executionRole: 'NURSE', specification: '', dosageForm: '', doseUnit: '', doseUnitsPerStock: undefined as number | undefined })
const form = reactive(blank())
const nonbillable = computed(() => ['NURSING','DIET','OTHER'].includes(form.category))
const visibleItems = computed(() => items.value.filter(row =>
  (!categoryFilter.value || row.item_type===categoryFilter.value) &&
  (!keyword.value.trim() || `${row.item_code} ${row.name} ${row.specification || ''}`.toLowerCase().includes(keyword.value.trim().toLowerCase()))
))
async function load() {
  const [catalog, routesData] = await Promise.all([getData<Row[]>('/order-catalog'), getData<Row[]>('/medication-routes')])
  items.value = catalog; routeOptions.value = routesData
}
function edit(row?: Row) {
  Object.assign(form, row ? { id: row.id, code: row.item_code, name: row.name, category: row.item_type, unit: row.unit, price: Number(row.unit_price), billable: Boolean(row.billable), allowFractionalQuantity: Boolean(row.allow_fractional_quantity), executionRole: row.execution_role==='DOCTOR' ? 'DOCTOR' : 'NURSE', specification: row.specification || '', dosageForm: row.dosage_form || '', doseUnit: row.dose_unit || '', doseUnitsPerStock: row.dose_units_per_stock ? Number(row.dose_units_per_stock) : undefined } : blank())
  routes.value = row?.allowed_routes ? String(row.allowed_routes).split(',') : []; editing.value = true
}
async function save() {
  if (busy.value || !form.code.trim() || !form.name.trim() || !form.unit.trim()) return
  busy.value = true
  try {
    await postData('/order-catalog', { ...form, billable: nonbillable.value ? false : form.billable, price: nonbillable.value || !form.billable ? 0 : form.price, doseUnit: form.category==='DRUG' ? form.doseUnit : null, doseUnitsPerStock: form.category==='DRUG' ? form.doseUnitsPerStock : null, allowedRoutes: form.category==='DRUG' ? routes.value.join(',') : null })
    ElMessage.success('项目资料已保存，已开医嘱快照不受影响'); editing.value = false; await load()
  } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <el-dialog :model-value="true" title="医嘱项目资料" width="1000px" style="max-width:calc(100vw - 32px)" @close="emit('close')">
    <div class="toolbar"><el-button type="primary" @click="edit()">新增项目</el-button><el-input v-model="keyword" clearable placeholder="编码、名称或规格" style="width:220px"/><el-select v-model="categoryFilter" clearable placeholder="全部类别" style="width:150px"><el-option v-for="(label,key) in orderCategories" :key="key" :label="label" :value="key"/></el-select><span class="muted">药品换算按实物规格维护。</span></div>
    <el-table :data="visibleItems" max-height="480"><el-table-column prop="item_code" label="编码" min-width="100"/><el-table-column prop="name" label="项目" min-width="170"/><el-table-column prop="specification" label="规格" min-width="140"/><el-table-column label="类别" width="90"><template #default="{row}">{{ orderCategories[row.item_type] }}</template></el-table-column><el-table-column label="计价" min-width="110"><template #default="{row}">{{ row.billable ? '¥'+row.unit_price+'/'+row.unit : '不收费' }}</template></el-table-column><el-table-column label="用量换算" min-width="190"><template #default="{row}">{{ row.item_type==='DRUG' ? (row.dose_unit ? '1 '+row.unit+' = '+row.dose_units_per_stock+' '+row.dose_unit : '待维护，不能开立') : '按项目执行' }}</template></el-table-column><el-table-column prop="allowed_routes" label="途径" min-width="130"/><el-table-column width="80"><template #default="{row}"><el-button link type="primary" @click="edit(row)">维护</el-button></template></el-table-column></el-table>
    <el-dialog v-model="editing" append-to-body title="维护项目资料" width="720px" style="max-width:calc(100vw - 32px)" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy">
      <el-form label-position="top" :disabled="busy" class="catalog-form">
        <el-form-item label="项目编码" required><el-input v-model="form.code" maxlength="32"/></el-form-item><el-form-item label="名称" required><el-input v-model="form.name" maxlength="120"/></el-form-item>
        <el-form-item label="类别" required><el-select v-model="form.category" :disabled="Boolean(form.id)"><el-option v-for="(label,key) in orderCategories" :key="key" :label="label" :value="key"/></el-select></el-form-item><el-form-item label="库存 / 计价单位" required><el-input v-model="form.unit" :disabled="Boolean(form.id)" maxlength="20" placeholder="盒、袋、次等"/></el-form-item>
        <el-form-item v-if="!nonbillable" label="收费"><el-switch v-model="form.billable"/></el-form-item><el-form-item v-if="!nonbillable && form.billable" label="单价（元）"><el-input-number v-model="form.price" :min="0.01" :precision="2"/></el-form-item>
        <el-form-item label="允许小数数量"><el-switch v-model="form.allowFractionalQuantity"/></el-form-item><el-form-item v-if="form.category!=='DRUG'" label="执行岗位"><el-select v-model="form.executionRole"><el-option label="护士" value="NURSE"/><el-option label="医生" value="DOCTOR"/></el-select></el-form-item>
        <template v-if="form.category==='DRUG'">
          <el-form-item label="规格" required><el-input v-model="form.specification" maxlength="120"/></el-form-item><el-form-item label="剂型" required><el-input v-model="form.dosageForm" maxlength="64" placeholder="片剂、胶囊、注射液等"/></el-form-item>
          <el-form-item label="单次用量单位" required><el-input v-model="form.doseUnit" maxlength="20" placeholder="片、粒、mL、mg 等"/></el-form-item><el-form-item :label="'每 1 '+(form.unit || '库存单位')+' 对应多少 '+(form.doseUnit || '用量单位')" required><el-input-number v-model="form.doseUnitsPerStock" :min="0.000001" :precision="6"/></el-form-item>
          <el-form-item label="允许的给药途径" class="full" required><el-select v-model="routes" multiple><el-option v-for="route in routeOptions" :key="route.code" :label="route.name" :value="route.name"/></el-select></el-form-item>
        </template>
      </el-form>
      <el-alert title="此处维护项目事实，不提供推荐剂量或自动配伍审核。已存在项目不能直接改变类别和库存单位。" type="info" :closable="false"/>
      <template #footer><el-button :disabled="busy" @click="editing=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存资料</el-button></template>
    </el-dialog>
  </el-dialog>
</template>
<style scoped>
.catalog-form { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0 16px; max-height: 58vh; overflow-y: auto; }
.catalog-form :deep(.el-input-number), .catalog-form :deep(.el-select) { width: 100%; }
.full { grid-column: span 2; }
@media(max-width:600px) { .catalog-form { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
