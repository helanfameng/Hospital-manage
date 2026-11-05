import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/api'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('hospital-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message || '网络异常，请稍后重试'
    const traceId = error.response?.data?.traceId
    ElMessage.error(traceId ? `${message}（追踪号：${traceId}）` : message)
    if (error.response?.status === 401 && !location.pathname.includes('/login')) {
      localStorage.removeItem('hospital-token')
      localStorage.removeItem('hospital-user')
      location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export async function getData<T>(url: string, params?: object): Promise<T> {
  const { data } = await api.get<ApiResponse<T>>(url, { params })
  return data.data
}

export async function postData<T>(url: string, body?: object): Promise<T> {
  const { data } = await api.post<ApiResponse<T>>(url, body)
  return data.data
}

export async function putData<T>(url: string, body?: object): Promise<T> {
  const { data } = await api.put<ApiResponse<T>>(url, body)
  return data.data
}

