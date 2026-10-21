import { jsonRequest, request } from '../../api/client'
import type { ContainerType, MaterialCategory, CratePalletInput, CratePalletRecord } from './types'

const base = '/api/crate-pallet-records'
export const getContainers = () => request<ContainerType[]>('/api/container-types')
export const getMaterials = () => request<MaterialCategory[]>('/api/material-categories')
export const getRecord = (id: number) => request<CratePalletRecord>(`${base}/${id}`)
export const previewRecord = (input: CratePalletInput, recordId?: number) =>
  jsonRequest<CratePalletRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: CratePalletInput) => jsonRequest<CratePalletRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: CratePalletInput) =>
  jsonRequest<CratePalletRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<CratePalletRecord[]>(`${base}?${params}`)
}
