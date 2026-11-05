<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getData, postData, putData } from '@/api/client'
import type { Row } from '@/types/api'
import { enumLabel, enumListLabel, permissionLabels, roleLabels, staffTypeLabels } from '@/utils/labels'
import { formatDateTime } from '@/utils/datetime'

const busy = ref(false)
const users = ref<Row[]>([])
const roles = ref<Row[]>([])
const staff = ref<Row[]>([])
const dialog = ref(false)
const form = reactive<any>({ staffId: undefined, username: '', displayName: '', password: '', roles: [] })
async function load() { [users.value, roles.value] = await Promise.all([getData<Row[]>('/admin/users'), getData<Row[]>('/admin/roles')]) }
async function open() { staff.value = (await getData<Row[]>('/admin/staff')).filter(s => s.active); Object.assign(form,{staffId:undefined,username:'',displayName:'',password:'',roles:[]}); dialog.value = true }
async function toggle(row: Row) {
  if (busy.value) return
  busy.value = true
  try { await ElMessageBox.confirm(`确认${row.enabled ? '停用' : '启用'}账号 ${row.username}？已有登录将失效。`,'账号状态确认',{type:'warning'}); await putData(`/admin/users/${row.id}/enabled`,{enabled:!row.enabled}); await load() }
  catch(e) { if(e!=='cancel' && e!=='close') console.error(e) }
  finally { busy.value = false }
}
async function create() {
  if (busy.value) return
  busy.value = true
  try { await postData('/admin/users', form); ElMessage.success('用户已创建'); dialog.value = false; await load() }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <div>
    <div class="page-header"><div><h2>用户与权限</h2><p>查看系统账号和角色权限，并为医院员工创建对应岗位的登录账号。</p></div><el-button type="primary" @click="open">创建用户</el-button></div>
    <div class="panel">
      <el-tabs>
        <el-tab-pane label="用户账号"><el-table :data="users"><el-table-column prop="username" label="用户名" /><el-table-column prop="display_name" label="显示名" /><el-table-column prop="staff_no" label="员工号" /><el-table-column label="岗位"><template #default="{ row }">{{ enumLabel(staffTypeLabels, row.staff_type) }}</template></el-table-column><el-table-column label="角色"><template #default="{ row }">{{ enumListLabel(roleLabels, row.role_codes) }}</template></el-table-column><el-table-column label="状态"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="最后登录（北京时间）" width="180"><template #default="{ row }">{{ formatDateTime(row.last_login_at) }}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button link :type="row.enabled ? 'danger' : 'primary'" :disabled="busy" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column></el-table></el-tab-pane>
        <el-tab-pane label="角色权限"><el-table :data="roles"><el-table-column prop="code" label="角色编码" /><el-table-column prop="name" label="角色名称" /><el-table-column label="权限集合" min-width="500"><template #default="{ row }">{{ enumListLabel(permissionLabels, row.permissions) }}</template></el-table-column></el-table></el-tab-pane>
      </el-tabs>
    </div>
    <el-dialog v-model="dialog" title="创建系统用户" width="600px">
      <el-form label-position="top"><el-form-item label="绑定员工"><el-select v-model="form.staffId" filterable class="full-width"><el-option v-for="s in staff" :key="s.id" :value="s.id" :label="`${s.name} · ${s.staff_no}`" /></el-select></el-form-item><el-row :gutter="16"><el-col :span="12"><el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item></el-col><el-col :span="12"><el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item></el-col></el-row><el-form-item label="初始密码（至少10位）"><el-input v-model="form.password" type="password" show-password /></el-form-item><el-form-item label="角色"><el-select v-model="form.roles" multiple class="full-width"><el-option v-for="r in roles" :key="r.id" :value="r.code" :label="r.name" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="busy" @click="create">创建账号</el-button></template>
    </el-dialog>
  </div>
</template>
