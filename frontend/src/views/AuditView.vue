<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getData } from '@/api/client'
import { auditActionLabels, auditLabel, auditObjectLabel, auditResultLabels, enumLabel, enumListLabel, roleLabels } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'

const data = ref<any>({ items: [] })
const username = ref('')
const action = ref('')
const currentPage = ref(1)
const actionOptions = Object.entries(auditActionLabels).map(([value, label]) => ({ value, label }))
async function load(page = 1) {
  data.value = await getData('/admin/audit-logs', { username: username.value, action: action.value, page, size: 20 })
  currentPage.value = page
}
onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-header"><div><h2>审计日志</h2><p>按用户名或操作类型查询系统操作记录，查看处理结果和追踪号。</p></div></div>
    <div class="panel">
      <div class="toolbar">
        <el-input v-model="username" placeholder="用户名" clearable style="width:180px" @keyup.enter="load()" />
        <el-select v-model="action" placeholder="操作" aria-label="操作" clearable filterable style="width:220px">
          <el-option v-for="option in actionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button @click="load()">查询</el-button>
      </div>
      <el-table :data="data.items">
        <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="角色" width="150"><template #default="{ row }">{{ enumListLabel(roleLabels, row.role_codes) }}</template></el-table-column>
        <el-table-column label="操作" width="180"><template #default="{ row }"><span :title="row.action">{{ auditLabel(auditActionLabels, row.action, '其他操作') }}</span></template></el-table-column>
        <el-table-column label="对象" min-width="180"><template #default="{ row }"><span :title="row.object_type">{{ auditObjectLabel(row.object_type, row.object_id) }}</span></template></el-table-column>
        <el-table-column label="结果" width="90"><template #default="{ row }">{{ enumLabel(auditResultLabels, row.result) }}</template></el-table-column>
        <el-table-column prop="trace_id" label="追踪号" min-width="220" />
      </el-table>
      <el-pagination v-model:current-page="currentPage" style="margin-top:18px;justify-content:flex-end" background layout="total,prev,pager,next" :total="data.total" :page-size="20" @current-change="load" />
    </div>
  </div>
</template>
