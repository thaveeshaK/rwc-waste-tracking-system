import { jsonRequest, request } from '../../api/client'
import type { ContainerType, MaterialCategory, ElectricalCratePalletInput, ElectricalCratePalletRecord } from './types'

const base = '/api/electrical-crate-pallet-records'
export const getContainers = () => request<ContainerType[]>('/api/container-types?context=ELECTRICAL_CRATE_PALLET')
export const getMaterials = () => request<MaterialCategory[]>('/api/material-categories?context=ELECTRICAL_CRATE_PALLET')
export const getRecord = (id: number) => request<ElectricalCratePalletRecord>(`${base}/${id}`)
export const previewRecord = (input: ElectricalCratePalletInput, recordId?: number) =>
  jsonRequest<ElectricalCratePalletRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: ElectricalCratePalletInput) => jsonRequest<ElectricalCratePalletRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: ElectricalCratePalletInput) =>
  jsonRequest<ElectricalCratePalletRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<ElectricalCratePalletRecord[]>(`${base}?${params}`)
}
