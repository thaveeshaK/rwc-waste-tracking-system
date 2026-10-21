import { jsonRequest, request } from '../../api/client'
import type { MaterialCategory, PickupInput, PickupRecord } from './types'
const base = '/api/pickup-records'
export const getMaterials = () => request<MaterialCategory[]>('/api/material-categories?context=PICKUP')
export const getRecord = (id: number) => request<PickupRecord>(`${base}/${id}`)
export const previewRecord = (input: PickupInput, recordId?: number) =>
  jsonRequest<PickupRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: PickupInput) => jsonRequest<PickupRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: PickupInput) => jsonRequest<PickupRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<PickupRecord[]>(`${base}?${params}`)
}
