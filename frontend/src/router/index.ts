import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import AppLayout from '@/layouts/AppLayout.vue'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '工作台' } },
      { path: 'patients', component: () => import('@/views/PatientsView.vue'), meta: { title: '患者管理', permission: 'PATIENT_READ' } },
      { path: 'admissions/new', redirect: '/admissions' },
      { path: 'admissions', component: () => import('@/views/AdmissionsView.vue'), meta: { title: '当前住院', permission: 'PATIENT_READ' } },
      { path: 'admissions/:id', component: () => import('@/views/AdmissionDetailView.vue'), meta: { title: '住院详情', permission: 'PATIENT_READ' } },
      { path: 'beds', component: () => import('@/views/BedsView.vue'), meta: { title: '床位总览', permission: 'PATIENT_READ' } },
      { path: 'orders', component: () => import('@/views/OrdersView.vue'), meta: { title: '医嘱管理', permission: 'PATIENT_READ' } },
      { path: 'executions', component: () => import('@/views/ExecutionsView.vue'), meta: { title: '待执行医嘱', permissionsAny: ['ORDER_EXECUTE','MEDICATION_ADMINISTER','ORDER_WRITE'] } },
      { path: 'inventory', component: () => import('@/views/InventoryView.vue'), meta: { title: '药品库存', permissionsAny: ['PHARMACY_EXECUTE','ADMIN_MANAGE'] } },
      { path: 'finance', component: () => import('@/views/FinanceView.vue'), meta: { title: '押金与费用', permission: 'DEPOSIT_MANAGE' } },
      { path: 'alerts', component: () => import('@/views/AlertsView.vue'), meta: { title: '欠费提醒', permission: 'DEPOSIT_MANAGE' } },
      { path: 'settlement', component: () => import('@/views/SettlementView.vue'), meta: { title: '出院结算', permission: 'SETTLEMENT_MANAGE' } },
      { path: 'statistics', component: () => import('@/views/StatisticsView.vue'), meta: { title: '统计分析', permission: 'STATISTICS_READ' } },
      { path: 'admin', component: () => import('@/views/AdminView.vue'), meta: { title: '系统管理', permission: 'ADMIN_MANAGE' } },
      { path: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志', permission: 'ADMIN_MANAGE' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async (to, from) => {
  const auth = useAuthStore()
  if (auth.token) {
    try { await auth.refresh() } catch { auth.logout() }
  }
  if (to.meta.public) return auth.authenticated ? '/dashboard' : true
  if (!auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (!auth.can(to.meta.permission as string | undefined) || (to.meta.permissionsAny && !auth.canAny(...to.meta.permissionsAny as string[]))) {
    ElMessage.warning((to.meta.permissionDeniedMessage as string | undefined) || '当前账号无权访问该功能')
    return from.matched.length ? false : '/dashboard'
  }
  return true
})

export default router
