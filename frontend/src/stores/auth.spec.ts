import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('starts unauthenticated without persisted credentials', () => {
    const store = useAuthStore()
    expect(store.authenticated).toBe(false)
    expect(store.can('PATIENT_READ')).toBe(false)
  })

  it.each(['{broken', JSON.stringify({ roles: 'ADMIN', permissions: 'PATIENT_READ' })])('ignores invalid cached users: %s', (cached) => {
    localStorage.setItem('hospital-token', 'old-token')
    localStorage.setItem('hospital-user', cached)
    const store = useAuthStore()
    expect(store.authenticated).toBe(false)
    expect(store.can('PATIENT_READ')).toBe(false)
  })

  it('refreshes persisted permissions from the server', async () => {
    const user = { id: 2, username: 'doctor', displayName: '医生', roles: ['DOCTOR'], permissions: ['PATIENT_READ'] }
    vi.spyOn(api, 'get').mockResolvedValueOnce({ data: { data: user } })
    const store = useAuthStore()
    await store.refresh()
    expect(store.can('PATIENT_READ')).toBe(true)
    expect(store.can('ORDER_WRITE')).toBe(false)
    expect(JSON.parse(localStorage.getItem('hospital-user')!)).toEqual(user)
  })

  it('clears persisted credentials on logout', () => {
    localStorage.setItem('hospital-token', 'token')
    localStorage.setItem('hospital-user', JSON.stringify({ id: 1, username: 'a', displayName: 'A', roles: ['ADMIN'], permissions: [] }))
    setActivePinia(createPinia())
    const store = useAuthStore()
    expect(store.authenticated).toBe(true)
    store.logout()
    expect(localStorage.getItem('hospital-token')).toBeNull()
  })

  it('checks alternative permissions without granting unrelated access', () => {
    localStorage.setItem('hospital-token', 'token')
    localStorage.setItem('hospital-user', JSON.stringify({
      id: 2,
      username: 'doctor',
      displayName: '医生',
      roles: ['DOCTOR'],
      permissions: ['PATIENT_READ', 'ORDER_WRITE', 'STATISTICS_READ'],
    }))
    setActivePinia(createPinia())
    const store = useAuthStore()

    expect(store.canAny('DEPOSIT_MANAGE', 'SETTLEMENT_MANAGE')).toBe(false)
    expect(store.canAny('ORDER_WRITE', 'ORDER_EXECUTE')).toBe(true)
  })

  it('treats administrators as authorized for any permission group', () => {
    localStorage.setItem('hospital-token', 'token')
    localStorage.setItem('hospital-user', JSON.stringify({
      id: 1,
      username: 'admin',
      displayName: '管理员',
      roles: ['ADMIN'],
      permissions: [],
    }))
    setActivePinia(createPinia())
    const store = useAuthStore()

    expect(store.canAny('DEPOSIT_MANAGE', 'SETTLEMENT_MANAGE')).toBe(true)
  })
})
