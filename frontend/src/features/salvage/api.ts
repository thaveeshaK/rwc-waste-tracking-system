import { jsonRequest, request } from '../../api/client'
import type { MaterialCategory, SalvageInput, SalvageRecord } from './types'
const base = '/api/salvage-records'
export const getMaterials = (direction: 'UP' | 'DOWN') => request<MaterialCategory[]>(`/api/material-categories?context=SALVAGE_${direction}`)
export const getRecord = (id: number) => request<SalvageRecord>(`${base}/${id}`)
export const previewRecord = (input: SalvageInput, recordId?: number) =>
  jsonRequest<SalvageRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: SalvageInput) => jsonRequest<SalvageRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: SalvageInput) => jsonRequest<SalvageRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<SalvageRecord[]>(`${base}?${params}`)
}
