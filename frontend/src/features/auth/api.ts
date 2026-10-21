import { request } from '../../api/client'

export interface User {
  id: number
  username: string
  role: 'STAFF' | 'ADMIN'
  operationalDate: string
}

export const currentUser = () => request<User>('/api/auth/me')
export const logout = () => request<void>('/api/auth/logout', { method: 'POST' })

export async function login(username: string, password: string): Promise<User> {
  await request<void>('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password }),
  })
  return currentUser()
}
