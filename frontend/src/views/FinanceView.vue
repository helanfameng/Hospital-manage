<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from '@/components/AdmissionPicker.vue'
import type { Row } from '@/types/api'
import { depositTypeLabels, enumLabel, medicalItemTypeLabels, paymentMethodLabels } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'
import { executionQuantity } from '@/utils/orders'

const admissionId = ref<number>()
const selected = ref<Row>()
const account = ref<Row>()
const deposits = ref<Row[]>([])
const charges = ref<Row[]>([])
const amount = ref(1000)
const type = ref('SUPPLEMENT')
const paymentMethod = ref('CASH')
const busy = ref(false)
const requestNo = ref(crypto.randomUUID())
const editable = computed(() => ['IN_HOSPITAL', 'PENDING_DISCHARGE'].includes(account.value?.status))
watch([admissionId, amount, type, paymentMethod], () => { requestNo.value = crypto.randomUUID() })
watch(admissionId, () => { account.value = undefined; deposits.value = []; charges.value = []; void load() })
async function load() {
  const id = admissionId.value
  if (!id) return
  const [a, d, c] = await Promise.all([getData<Row>(`/admissions/${id}`), getData<Row[]>('/deposits', { admissionId: id }), getData<Row[]>('/charges', { admissionId: id })])
  if (admissionId.value !== id) return
  account.value = a; deposits.value = d; charges.value = c
}
async function save() {
  if (!admissionId.value || !editable.value || busy.value) return
  busy.value = true
  try {
    await ElMessageBox.confirm(`${selected.value?.patient_name}（${selected.value?.admission_no}）：确认以${enumLabel(paymentMethodLabels, paymentMethod.value)}${type.value === 'REFUND' ? '退款' : '补交押金'} ¥${amount.value}？`, '核对患者、金额与支付方式', { type: 'warning' })
    await postData('/deposits', { admissionId: admissionId.value, type: type.value, amount: amount.value, paymentMethod: paymentMethod.value, note: type.value === 'REFUND' ? '住院期间退款' : '住院期间补交押金', requestNo: requestNo.value })
    requestNo.value = crypto.randomUUID()
    ElMessage.success('流水已登记，余额和提醒已同步')
    await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') console.error(e) }
  finally { busy.value = false }
}
async function reverse(row: Row) {
  if (busy.value) return
  busy.value = true
  try {
    const { value } = await ElMessageBox.prompt(`确认完整冲正流水 ${row.transaction_no}（¥${row.amount}）？`, '押金冲正原因', { inputPattern: /\S/, inputErrorMessage: '请输入原因', type: 'warning' })
    await postData(`/deposits/${row.id}/reverse`, { reason: value.trim() })
    ElMessage.success('原流水已冲正，不能再次冲正')
    await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') console.error(e) }
  finally { busy.value = false }
}
</script>
<template>
  <div>
    <div class="page-header"><div><h2>押金与费用</h2><p>先搜索并确认患者，再办理补交、退款或冲正。已出院账目只可查询。</p></div></div>
    <div class="panel">
      <div class="toolbar"><div style="width:380px"><AdmissionPicker v-model="admissionId" :disabled="busy" @change="selected=$event" /></div><el-button :disabled="!admissionId || busy" @click="load">刷新账目</el-button></div>
      <el-descriptions v-if="account" border :column="3" style="margin-bottom:18px"><el-descriptions-item label="患者">{{ account.patient_name }} · {{ account.admission_no }}</el-descriptions-item><el-descriptions-item label="押金 / 费用">¥{{ account.deposit_balance }} / ¥{{ account.total_charge }}</el-descriptions-item><el-descriptions-item label="可用余额">¥{{ account.available_balance }}</el-descriptions-item></el-descriptions>
      <div v-if="editable" class="toolbar"><el-select v-model="type" :disabled="busy" style="width:150px"><el-option label="补交押金" value="SUPPLEMENT"/><el-option label="退还可用余额" value="REFUND"/></el-select><el-input-number v-model="amount" :disabled="busy" :min="0.01" :max="9999999999.99" :precision="2"/><el-select v-model="paymentMethod" :disabled="busy" aria-label="支付方式" style="width:130px"><el-option v-for="(label, value) in paymentMethodLabels" :key="value" :label="label" :value="value" /></el-select><el-button type="primary" :loading="busy" @click="save">确认登记</el-button></div>
      <el-tabs>
        <el-tab-pane label="押金流水">
          <el-table :data="deposits">
            <el-table-column prop="transaction_no" label="流水号" width="220" show-overflow-tooltip/>
            <el-table-column label="类型" min-width="100" align="center"><template #default="{row}">{{ enumLabel(depositTypeLabels,row.transaction_type) }}</template></el-table-column>
            <el-table-column prop="amount" label="金额" min-width="110" align="right"/>
            <el-table-column label="支付方式" min-width="100" align="center"><template #default="{row}">{{ enumLabel(paymentMethodLabels,row.payment_method) }}</template></el-table-column>
            <el-table-column label="时间" min-width="180" align="center"><template #default="{row}">{{ formatDateTime(row.transaction_time) }}</template></el-table-column>
            <el-table-column label="冲正原因" min-width="160" show-overflow-tooltip><template #default="{row}">{{ row.transaction_type === 'REVERSAL' ? (row.note || '未记录') : '—' }}</template></el-table-column>
            <el-table-column label="操作" min-width="90" align="center"><template #default="{row}"><el-button v-if="editable && row.transaction_type!=='REVERSAL' && !row.is_reversed" link type="danger" :disabled="busy" @click="reverse(row)">冲正</el-button><span v-else-if="row.is_reversed">已冲正</span></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="费用明细">
          <el-table :data="charges">
            <el-table-column prop="charge_no" label="费用号" width="220" show-overflow-tooltip/>
            <el-table-column label="来源医嘱号" min-width="190" show-overflow-tooltip><template #default="{row}">{{ row.order_no || (row.item_type==='BED_FEE' ? '系统自动费用' : '无关联医嘱') }}</template></el-table-column>
            <el-table-column prop="item_name" label="项目" min-width="150" show-overflow-tooltip/>
            <el-table-column label="类别" min-width="90" align="center"><template #default="{row}">{{ enumLabel(medicalItemTypeLabels,row.item_type) }}</template></el-table-column>
            <el-table-column label="数量" min-width="110" align="right"><template #default="{row}">{{ executionQuantity(row) }}</template></el-table-column>
            <el-table-column prop="unit_price_snapshot" label="单价快照" min-width="110" align="right"/>
            <el-table-column prop="amount" label="金额" min-width="110" align="right"/>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>
