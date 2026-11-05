<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, postData } from '@/api/client'
import type { Row } from '@/types/api'
import { alertLevelLabels, enumLabel } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'

const data = ref<any>({ items: [] })
const status = ref('OPEN')
const page = ref(1)
async function load() { data.value = await getData('/alerts', { status: status.value, page: page.value, size: 20 }) }
async function resolve(row: Row) {
  await postData(`/alerts/${row.id}/resolve`)
  ElMessage.success('提醒已处理')
  await load()
}
onMounted(load)
</script>

<template>
  <div>
    <div class="page-header"><div><h2>欠费提醒</h2><p>查看待处理和已处理的欠费患者，核对余额后标记跟进结果。</p></div></div>
    <div class="panel">
      <div class="toolbar"><el-radio-group v-model="status" @change="page=1;load()"><el-radio-button label="OPEN">待处理</el-radio-button><el-radio-button label="RESOLVED">已处理</el-radio-button></el-radio-group></div>
      <el-table :data="data.items">
        <el-table-column label="等级" width="100"><template #default="{ row }"><el-tag :type="row.level === 'CRITICAL' ? 'danger' : 'warning'">{{ enumLabel(alertLevelLabels, row.level) }}</el-tag></template></el-table-column><el-table-column prop="patient_name" label="患者" /><el-table-column prop="admission_no" label="住院号" /><el-table-column prop="department_name" label="科室" /><el-table-column prop="balance_snapshot" label="余额快照" /><el-table-column prop="threshold_snapshot" label="阈值" /><el-table-column label="产生时间" width="180"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column><el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="row.status==='OPEN'" link type="primary" @click="resolve(row)">标记处理</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :total="data.total || 0" :page-size="20" layout="total,prev,pager,next" @current-change="load" />
    </div>
  </div>
</template>
