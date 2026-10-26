import { request, ApiError } from '../../api/client'
import type { Filters } from './types'
export function query(filters: Filters): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) if (value) params.set(key, value)
  return params.toString()
}
export { request }
export async function download(path: string) {
  const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(problem?.detail ?? 'Export failed. Please retry.', response.status)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] ?? 'report'
  document.body.append(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
