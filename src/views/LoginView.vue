<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (loading.value) return
  if (!form.username || !form.password) return ElMessage.warning('请输入用户名和密码')
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    await router.replace(String(route.query.redirect || '/dashboard'))
  } catch {
    // The API interceptor already displays the failure; keep the form usable.
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand">
        <div class="brand-mark">H</div>
        <div><strong>Hospital One</strong><span>小型医院住院信息系统</span></div>
      </div>
      <h1>让每一次住院，都有清晰可靠的业务轨迹。</h1>
      <p>从入院分床到医嘱执行、自动计费与出院结算，关键事实完整保留，异常操作及时阻断。</p>
      <div class="story-points">
        <div><strong>4</strong>关键事务闭环</div>
        <div><strong>6</strong>岗位角色协同</div>
        <div><strong>流水</strong>费用与执行可追溯</div>
      </div>
    </section>
    <section class="login-form-wrap">
      <div class="login-card">
        <p class="eyebrow">SECURE ACCESS</p>
        <h2>欢迎回来</h2>
        <p>请使用医院内部账号进入对应工作台。</p>
        <el-form label-position="top" @keyup.enter="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" placeholder="请输入用户名" :prefix-icon="'User'" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" size="large" type="password" show-password placeholder="请输入密码" :prefix-icon="'Lock'" />
          </el-form-item>
          <el-button class="full-width" size="large" type="primary" :loading="loading" @click="submit">登录系统</el-button>
        </el-form>
        <p class="muted" style="margin-top:18px;font-size:12px">账号由管理员创建，连续失败请联系系统管理员。</p>
      </div>
    </section>
  </div>
</template>
