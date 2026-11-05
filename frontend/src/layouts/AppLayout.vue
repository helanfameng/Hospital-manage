<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { postData } from '@/api/client'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const passwordDialog = ref(false)
const passwordBusy = ref(false)
const password = reactive({ oldPassword: '', newPassword: '', confirmation: '' })
function openPassword() {
  Object.assign(password, { oldPassword: '', newPassword: '', confirmation: '' })
  passwordDialog.value = true
}
async function changePassword() {
  if (passwordBusy.value) return
  if (password.newPassword !== password.confirmation) { ElMessage.warning('两次新密码不一致'); return }
  if (password.newPassword.length < 10 || new TextEncoder().encode(password.newPassword).length > 72) {
    ElMessage.warning('新密码至少10个字符，UTF-8编码不超过72字节'); return
  }
  passwordBusy.value = true
  try {
    await postData('/auth/password', { oldPassword: password.oldPassword, newPassword: password.newPassword })
    passwordDialog.value = false
    Object.assign(password, { oldPassword: '', newPassword: '', confirmation: '' })
    ElMessage.success('密码已修改，旧会话已失效，请重新登录')
    logout()
  } finally { passwordBusy.value = false }
}

const menu = computed(() =>
  [
    { path: '/dashboard', label: '工作台', icon: 'DataBoard' },
    { path: '/patients', label: '患者管理', icon: 'User', permission: 'PATIENT_READ' },
    { path: '/admissions', label: '当前住院', icon: 'FirstAidKit', permission: 'PATIENT_READ' },
    { path: '/beds', label: '床位总览', icon: 'Grid', permission: 'PATIENT_READ' },
    { path: '/orders', label: '医嘱管理', icon: 'Tickets', permission: 'PATIENT_READ' },
    { path: '/executions', label: '待执行任务', icon: 'CircleCheck', permissionsAny: ['ORDER_EXECUTE','MEDICATION_ADMINISTER','ORDER_WRITE'] },
    { path: '/inventory', label: '药品库存', icon: 'Box', permissionsAny: ['PHARMACY_EXECUTE','ADMIN_MANAGE'] },
    { path: '/finance', label: '押金与费用', icon: 'Wallet', permission: 'DEPOSIT_MANAGE' },
    { path: '/alerts', label: '欠费提醒', icon: 'Warning', permission: 'DEPOSIT_MANAGE' },
    { path: '/settlement', label: '出院结算', icon: 'Finished', permission: 'SETTLEMENT_MANAGE' },
    { path: '/statistics', label: '统计分析', icon: 'TrendCharts', permission: 'STATISTICS_READ' },
    { path: '/admin', label: '系统管理', icon: 'Setting', permission: 'ADMIN_MANAGE' },
    { path: '/audit', label: '审计日志', icon: 'Operation', permission: 'ADMIN_MANAGE' },
  ].filter((item) => auth.can(item.permission) && (!item.permissionsAny || auth.canAny(...item.permissionsAny))),
)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">H</div>
        <div v-if="!collapsed">
          <strong>Hospital One</strong>
          <span>住院管理中心</span>
        </div>
      </div>
      <!-- Hide labels immediately; the app shell owns the width transition. -->
      <el-menu :default-active="route.path" router class="side-menu" :collapse="collapsed" :collapse-transition="false">
        <el-menu-item v-for="item in menu" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.label }}</template>
        </el-menu-item>
      </el-menu>
      <button class="collapse-button" :aria-label="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起导航</span>
      </button>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div>
          <p class="eyebrow">INPATIENT OPERATIONS</p>
          <h1>{{ route.meta.title }}</h1>
        </div>
        <el-dropdown>
          <button class="user-chip">
            <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) }}</span>
            <span>{{ auth.user?.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ auth.user?.roles?.join(' / ') }}</el-dropdown-item>
              <el-dropdown-item @click="openPassword">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <section class="page-content">
        <router-view />
      </section>
    </main>
    <el-dialog v-model="passwordDialog" title="修改密码" width="440px" :close-on-click-modal="false" @closed="Object.assign(password, { oldPassword: '', newPassword: '', confirmation: '' })">
      <el-form label-position="top">
        <el-form-item label="原密码"><el-input v-model="password.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码（至少10个字符）"><el-input v-model="password.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="password.confirmation" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="passwordBusy" @click="passwordDialog=false">取消</el-button><el-button type="primary" :loading="passwordBusy" :disabled="!password.oldPassword || !password.newPassword || !password.confirmation" @click="changePassword">保存并重新登录</el-button></template>
    </el-dialog>
  </div>
</template>
