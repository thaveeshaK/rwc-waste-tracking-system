import { jsonRequest, request } from '../../api/client'
import type { MaterialCategory, CollectableInput, CollectableRecord } from './types'
const base = '/api/collectable-records'
export const getMaterials = () => request<MaterialCategory[]>('/api/material-categories?context=COLLECTABLES')
export const getRecord = (id: number) => request<CollectableRecord>(`${base}/${id}`)
export const previewRecord = (input: CollectableInput, recordId?: number) =>
  jsonRequest<CollectableRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: CollectableInput) => jsonRequest<CollectableRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: CollectableInput) => jsonRequest<CollectableRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<CollectableRecord[]>(`${base}?${params}`)
}
