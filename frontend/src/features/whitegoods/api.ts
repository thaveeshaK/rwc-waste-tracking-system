import { jsonRequest, request } from '../../api/client'
import type { WhitegoodsInput, WhitegoodsRecord } from './types'
const base = '/api/whitegoods-records'
export const getRecord = (id: number) => request<WhitegoodsRecord>(`${base}/${id}`)
export const previewRecord = (input: WhitegoodsInput, recordId?: number) =>
  jsonRequest<WhitegoodsRecord>(`${base}/preview${recordId ? `?recordId=${recordId}` : ''}`, 'POST', input)
export const submitRecord = (input: WhitegoodsInput) => jsonRequest<WhitegoodsRecord>(base, 'POST', input)
export const updateRecord = (id: number, input: WhitegoodsInput) => jsonRequest<WhitegoodsRecord>(`${base}/${id}`, 'PUT', input)
export const deleteRecord = (id: number) => request<void>(`${base}/${id}`, { method: 'DELETE' })
export function getRecords(from = '', to = '') {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return request<WhitegoodsRecord[]>(`${base}?${params}`)
}
export const getMonthlySummary = (month: string) => request<import('./types').MonthlySummary>(`${base}/monthly-summary?month=${encodeURIComponent(month)}`)
