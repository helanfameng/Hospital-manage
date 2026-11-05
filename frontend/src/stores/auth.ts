import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { ApiResponse, AuthUser } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('hospital-token') || '')
  function restoreUser(): AuthUser | null {
    try {
      const cached = JSON.parse(localStorage.getItem('hospital-user') || 'null')
      return cached && typeof cached.id === 'number' && typeof cached.username === 'string'
        && Array.isArray(cached.roles) && Array.isArray(cached.permissions) ? cached : null
    } catch { return null }
  }
  const user = ref<AuthUser | null>(restoreUser())
  const authenticated = computed(() => Boolean(token.value && user.value))

  async function login(username: string, password: string) {
    const response = await api.post<ApiResponse<{ token: string; user: AuthUser }>>('/auth/login', {
      username,
      password,
    })
    token.value = response.data.data.token
    user.value = response.data.data.user
    localStorage.setItem('hospital-token', token.value)
    localStorage.setItem('hospital-user', JSON.stringify(user.value))
  }

  async function refresh() {
    const response = await api.get<ApiResponse<AuthUser>>('/auth/me')
    user.value = response.data.data
    localStorage.setItem('hospital-user', JSON.stringify(user.value))
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('hospital-token')
    localStorage.removeItem('hospital-user')
  }

  function can(permission?: string) {
    if (!permission) return true
    return Boolean(user.value?.permissions?.includes(permission) || user.value?.roles?.includes('ADMIN'))
  }

  function canAny(...permissions: string[]) {
    return permissions.some((permission) => can(permission))
  }

  return { token, user, authenticated, login, logout, refresh, can, canAny }
})
