import { jsonRequest, request } from '../../api/client'
import type { ContainerType, EwastePickupInput, EwastePickupRecord } from './types'
const base = '/api/ewaste-pickups'
export const getContainers = () => request<ContainerType[]>('/api/container-types?context=EWASTE_PICKUP')
export const getOrganisations = (query: string) => request<string[]>(base+'/organisations?query='+encodeURIComponent(query))
export const getRecord = (id: number) => request<EwastePickupRecord>(`${base}/${id}`)
export const previewRecord = (input: EwastePickupInput, recordId?: number) =>
  jsonRequest<EwastePickupRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: EwastePickupInput) => jsonRequest<EwastePickupRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: EwastePickupInput) => jsonRequest<EwastePickupRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<EwastePickupRecord[]>(`${base}?${params}`)
}
