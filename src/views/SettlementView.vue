<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData } from '@/api/client'
import AdmissionPicker from '@/components/AdmissionPicker.vue'
import type { Row } from '@/types/api'
import { admissionStatusLabels, enumLabel } from '@/utils/labels'
const admissionId = ref<number>()
const selected = ref<Row>()
const checkedId = ref<number>()
const check = ref<Row>()
const result = ref<Row>()
const busy = ref(false)
watch(admissionId, () => { checkedId.value = undefined; check.value = undefined; result.value = undefined })
const ready = computed(() => check.value?.status === 'PENDING_DISCHARGE' && checkedId.value === admissionId.value && Number(check.value?.pending_order_count) === 0 && Number(check.value?.amount_due) === 0 && !result.value)
async function precheck() {
  if (!admissionId.value || busy.value) return
  const id = admissionId.value
  busy.value = true
  try {
    const value = await getData<Row>(`/settlements/${id}/precheck`)
    if (id !== admissionId.value) return
    check.value = value; checkedId.value = id; result.value = undefined
    if (value.status === 'DISCHARGED') result.value = await getData<Row>(`/settlements/${id}`)
  } finally { busy.value = false }
}
async function settle() {
  if (!ready.value || busy.value) return
  const id = checkedId.value
  busy.value = true
  try {
    await ElMessageBox.confirm(`确认患者 ${selected.value?.patient_name}（${selected.value?.admission_no}）出院结算？费用 ¥${check.value?.total_charge}，退款 ¥${check.value?.refund_amount}。系统会再次校验并释放床位。`, '确认患者与结算金额', { type: 'warning' })
    if (id !== admissionId.value) return
    result.value = await postData<Row>(`/settlements/${id}`)
    ElMessage.success('结算完成，床位已释放')
  } catch (e) { if (e !== 'cancel' && e !== 'close') { check.value = undefined; checkedId.value = undefined } }
  finally { busy.value = false }
}
</script>
<template>
  <div><div class="page-header"><div><h2>出院结算</h2><p>搜索患者并确认身份；先申请出院，再核对医嘱与账目。切换患者必须重新预检。</p></div></div>
    <div class="panel" style="max-width:1000px">
      <div class="toolbar"><div style="width:420px"><AdmissionPicker v-model="admissionId" :disabled="busy" @change="selected=$event"/></div><el-button type="primary" :disabled="!admissionId" :loading="busy" @click="precheck">结算预检 / 查看结算单</el-button></div>
      <template v-if="check">
        <el-alert v-if="check.status==='IN_HOSPITAL'" type="warning" :closable="false" title="请先由住院处完成出院申请"/>
        <el-alert v-if="Number(check.pending_order_count)>0" type="error" :closable="false" :title="`存在 ${check.pending_order_count} 条未完成医嘱（含草稿），不能结算`"/>
        <el-descriptions :column="2" border style="margin-top:18px"><el-descriptions-item label="患者">{{ selected?.patient_name }} · {{ selected?.admission_no }}</el-descriptions-item><el-descriptions-item label="状态">{{ enumLabel(admissionStatusLabels,check.status) }}</el-descriptions-item><el-descriptions-item label="费用总额">¥{{ check.total_charge }}</el-descriptions-item><el-descriptions-item label="押金余额">¥{{ check.deposit_balance }}</el-descriptions-item><el-descriptions-item label="应补缴">¥{{ check.amount_due }}</el-descriptions-item><el-descriptions-item label="应退款">¥{{ check.refund_amount }}</el-descriptions-item></el-descriptions>
        <el-button style="margin-top:20px" type="danger" :disabled="!ready" :loading="busy" @click="settle">确认出院结算</el-button>
      </template>
      <el-result v-if="result" icon="success" title="已完成结算" :sub-title="`结算单号：${result.settlement_no}；结算退款：¥${result.refund_amount}`"/>
    </div>
  </div>
</template>
