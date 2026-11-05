<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, postData, putData } from '@/api/client'
import { formatDateTime } from '@/utils/datetime'
import type { PageResult, Row } from '@/types/api'

const items = ref<Row[]>([])
const keyword = ref('')
const status = ref('')
const loading = ref(false)
const busy = ref(false)
const selected = ref<Row | null>(null)
const operation = ref<'inbound' | 'adjustment' | 'threshold' | ''>('')
const form = reactive<{ quantity?: number; actualQuantity?: number; threshold?: number; note: string }>({ note: '' })
const historyOpen = ref(false)
const history = ref<PageResult<Row>>({ items: [], total: 0, page: 1, size: 20 })

const visibleItems = computed(() => items.value.filter(item => !status.value || item.stock_status === status.value))
const operationOpen = computed({ get: () => Boolean(operation.value), set: value => { if (!value) operation.value = '' } })
const dialogTitle = computed(() => operation.value === 'inbound' ? '药品入库' : operation.value === 'adjustment' ? '库存盘点调整' : '设置低库存预警')

function numberValue(value: unknown) { return Number(value || 0) }
function statusLabel(value: string) { return value === 'OUT_OF_STOCK' ? '缺货' : value === 'LOW' ? '低库存' : '正常' }
function statusType(value: string) { return value === 'OUT_OF_STOCK' ? 'danger' : value === 'LOW' ? 'warning' : 'success' }
function transactionLabel(value: string) { return ({ INBOUND: '入库', DISPENSE: '发药', RETURN: '退药', ADJUSTMENT: '盘点调整' } as Record<string, string>)[value] || value }

async function load() {
  loading.value = true
  try { items.value = await getData('/inventory', keyword.value.trim() ? { keyword: keyword.value.trim() } : undefined) }
  finally { loading.value = false }
}

function openOperation(item: Row, type: 'inbound' | 'adjustment' | 'threshold') {
  selected.value = item
  operation.value = type
  Object.assign(form, {
    quantity: undefined,
    actualQuantity: type === 'adjustment' ? numberValue(item.stock_quantity) : undefined,
    threshold: type === 'threshold' && item.low_stock_threshold != null ? numberValue(item.low_stock_threshold) : undefined,
    note: '',
  })
}

async function submit() {
  if (!selected.value || busy.value) return
  if (operation.value === 'inbound' && (!form.quantity || form.quantity <= 0)) { ElMessage.warning('请输入大于0的入库数量'); return }
  if (operation.value === 'adjustment' && (form.actualQuantity == null || form.actualQuantity < 0 || !form.note.trim())) { ElMessage.warning('请填写实际库存和调整原因'); return }
  if (operation.value === 'inbound' && !form.note.trim()) { ElMessage.warning('请填写入库原因或单据号'); return }
  busy.value = true
  try {
    if (operation.value === 'inbound') await postData(`/inventory/${selected.value.id}/inbound`, { quantity: form.quantity, note: form.note.trim() })
    if (operation.value === 'adjustment') await postData(`/inventory/${selected.value.id}/adjustment`, { actualQuantity: form.actualQuantity, note: form.note.trim() })
    if (operation.value === 'threshold') await putData(`/inventory/${selected.value.id}/threshold`, { threshold: form.threshold ?? null })
    ElMessage.success(operation.value === 'inbound' ? '入库成功' : operation.value === 'adjustment' ? '库存已按盘点结果调整' : '预警阈值已保存')
    operation.value = ''
    await load()
  } finally { busy.value = false }
}

async function openHistory(item: Row, page = 1) {
  selected.value = item
  historyOpen.value = true
  history.value = await getData(`/inventory/${item.id}/transactions`, { page, size: 20 })
}

function changeHistoryPage(page: number) {
  if (selected.value) void openHistory(selected.value, page)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-header">
      <div><h2>药品库存</h2><p>办理药品入库和盘点调整，库存变化均保留不可覆盖的流水记录。</p></div>
    </div>
    <div class="panel">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="药品编码、名称或规格" style="width:260px" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-radio-group v-model="status">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="OUT_OF_STOCK">缺货</el-radio-button>
          <el-radio-button value="LOW">低库存</el-radio-button>
          <el-radio-button value="NORMAL">正常</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="loading" :data="visibleItems">
        <el-table-column prop="item_code" label="编码" width="105" />
        <el-table-column prop="name" label="药品" min-width="150" />
        <el-table-column prop="specification" label="规格" min-width="130" />
        <el-table-column label="当前库存" width="125">
          <template #default="{ row }"><strong>{{ row.stock_quantity }} {{ row.unit }}</strong></template>
        </el-table-column>
        <el-table-column label="预警阈值" width="110">
          <template #default="{ row }">{{ row.low_stock_threshold == null ? '未设置' : `${row.low_stock_threshold} ${row.unit}` }}</template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }"><el-tag :type="statusType(row.stock_status)">{{ statusLabel(row.stock_status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近变动" width="170">
          <template #default="{ row }">{{ row.last_transaction_at ? formatDateTime(row.last_transaction_at) : '暂无流水' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="245" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openOperation(row, 'inbound')">入库</el-button>
            <el-button link type="primary" @click="openOperation(row, 'adjustment')">盘点</el-button>
            <el-button link @click="openOperation(row, 'threshold')">预警</el-button>
            <el-button link @click="openHistory(row)">流水</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !visibleItems.length" description="没有符合条件的药品" />
    </div>

    <el-dialog v-model="operationOpen" :title="dialogTitle" width="480px" :close-on-click-modal="false" :show-close="!busy">
      <p class="inventory-drug"><strong>{{ selected?.name }}</strong><span>当前库存：{{ selected?.stock_quantity }} {{ selected?.unit }}</span></p>
      <el-form label-position="top" :disabled="busy">
        <el-form-item v-if="operation === 'inbound'" :label="`入库数量（${selected?.unit || ''}）`" required>
          <el-input-number v-model="form.quantity" :min="0" :precision="selected?.allow_fractional_quantity ? 3 : 0" />
        </el-form-item>
        <el-form-item v-if="operation === 'adjustment'" :label="`盘点实际库存（${selected?.unit || ''}）`" required>
          <el-input-number v-model="form.actualQuantity" :min="0" :precision="selected?.allow_fractional_quantity ? 3 : 0" />
        </el-form-item>
        <el-form-item v-if="operation === 'threshold'" :label="`低库存预警阈值（${selected?.unit || ''}）`">
          <el-input-number v-model="form.threshold" :min="0" :precision="selected?.allow_fractional_quantity ? 3 : 0" placeholder="留空表示不预警" />
        </el-form-item>
        <el-form-item v-if="operation !== 'threshold'" :label="operation === 'inbound' ? '入库原因或单据号' : '调整原因'" required>
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert v-if="operation === 'adjustment'" title="系统将根据实际库存计算差额，并新增调整流水；已有流水不会被修改。" type="info" :closable="false" />
      <template #footer><el-button :disabled="busy" @click="operation=''">取消</el-button><el-button type="primary" :loading="busy" @click="submit">确认</el-button></template>
    </el-dialog>

    <el-dialog v-model="historyOpen" :title="`${selected?.name || ''} · 库存流水`" width="900px">
      <el-table :data="history.items" max-height="520">
        <el-table-column label="类型" width="105"><template #default="{ row }">{{ transactionLabel(row.transaction_type) }}</template></el-table-column>
        <el-table-column label="变动" width="100"><template #default="{ row }"><span :class="Number(row.quantity) > 0 ? 'increase' : 'decrease'">{{ Number(row.quantity) > 0 ? '+' : '' }}{{ row.quantity }}</span></template></el-table-column>
        <el-table-column prop="before_quantity" label="变动前" width="100" />
        <el-table-column prop="after_quantity" label="变动后" width="100" />
        <el-table-column prop="note" label="原因" min-width="160" />
        <el-table-column label="操作人" width="120"><template #default="{ row }">{{ row.operator_name || row.operator_username || '系统初始化' }}</template></el-table-column>
        <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="history.page" :total="history.total" :page-size="history.size" layout="total,prev,pager,next" @current-change="changeHistoryPage" />
    </el-dialog>
  </div>
</template>

<style scoped>
.inventory-drug { display:flex; justify-content:space-between; gap:16px; color:#647b78; margin-top:0; }
.inventory-drug strong { color:#18302f; }
.el-input-number { width:100%; }
.increase { color:#15803d; font-weight:700; }
.decrease { color:#c2410c; font-weight:700; }
</style>
