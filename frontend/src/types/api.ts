export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId: string
  timestamp: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface AuthUser {
  id: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export type Row = Record<string, any>

